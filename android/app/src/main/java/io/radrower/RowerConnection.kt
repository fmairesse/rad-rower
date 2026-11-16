package io.radrower

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, INITIALIZING, STREAMING }

internal data class Reading(val value: Any?, val receivedAt: Long)

internal data class RowerUiState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val readings: Map<String, Reading> = emptyMap(),
    val message: String? = null,
    val controlling: Boolean = false,
) {
    fun sessionState(now: Long): Int? =
        readings["sessionState"]?.takeIf { now - it.receivedAt <= 5_000 }?.value as? Int
}

internal fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

internal fun hasBluetoothPermissions(context: Context): Boolean =
    bluetoothPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@SuppressLint("MissingPermission")
internal class RowerConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onMetrics: (Map<String, Any?>) -> Unit = {},
    private val onDisconnect: () -> Unit = {},
) {
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(RowerUiState())
    val state: StateFlow<RowerUiState> = mutableState
    private var connectionJob: Job? = null
    private var controlJob: Job? = null
    private var session: GattSession? = null
    private var generation = 0

    fun bluetoothEnabled(): Boolean = adapter?.isEnabled == true
    fun bluetoothAvailable(): Boolean = adapter != null

    fun connect() {
        if (connectionJob?.isActive == true) return
        if (!hasBluetoothPermissions(context)) {
            mutableState.value = mutableState.value.copy(message = "Bluetooth permission is required.")
            return
        }
        if (!bluetoothAvailable()) {
            mutableState.value = mutableState.value.copy(message = "Bluetooth is not available on this device.")
            return
        }
        if (!bluetoothEnabled()) {
            mutableState.value = mutableState.value.copy(message = "Turn on Bluetooth to connect.")
            return
        }
        mutableState.value = RowerUiState(connection = ConnectionState.SCANNING)
        val attempt = ++generation
        connectionJob = scope.launch {
            var current: GattSession? = null
            try {
                val device = scan() ?: error("Rower not found. Turn it on and try again.")
                mutableState.value = mutableState.value.copy(connection = ConnectionState.CONNECTING)
                current = GattSession()
                session = current
                current.open(device)
                mutableState.value = mutableState.value.copy(connection = ConnectionState.INITIALIZING)
                current.initialize()
                mutableState.value = mutableState.value.copy(connection = ConnectionState.STREAMING, message = null)
                current.poll()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (attempt == generation) {
                    mutableState.value = mutableState.value.copy(
                        message = error.message ?: "Bluetooth connection failed."
                    )
                }
            } finally {
                current?.close()
                if (attempt == generation) {
                    if (current != null) onDisconnect()
                    controlJob?.cancel()
                    controlJob = null
                    session = null
                    mutableState.value = mutableState.value.copy(
                        connection = ConnectionState.DISCONNECTED, controlling = false
                    )
                }
            }
        }
    }

    fun disconnect() {
        generation++
        controlJob?.cancel()
        controlJob = null
        connectionJob?.cancel()
        connectionJob = null
        if (session != null) onDisconnect()
        session = null
        mutableState.value = mutableState.value.copy(connection = ConnectionState.DISCONNECTED, controlling = false)
    }

    fun control(stop: Boolean) {
        val active = session ?: return
        val snapshot = mutableState.value
        val rowerState = snapshot.sessionState(SystemClock.elapsedRealtime())
        val steps = commandSteps(rowerState, stop) ?: return
        if (snapshot.connection != ConnectionState.STREAMING || snapshot.controlling) return
        mutableState.value = snapshot.copy(controlling = true, message = null)
        controlJob = scope.launch {
            try {
                runCommandSteps(steps) { step ->
                    active.command(step.messages, step.expectedState, step.required)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (session === active) {
                    mutableState.value = mutableState.value.copy(
                        message = error.message ?: "Session command failed."
                    )
                    connectionJob?.cancel()
                }
            } finally {
                if (session === active) {
                    mutableState.value = mutableState.value.copy(controlling = false)
                }
            }
        }
    }

    private suspend fun scan(): BluetoothDevice? = withTimeoutOrNull(12_000) {
        val scanner = adapter?.bluetoothLeScanner ?: error("Bluetooth scanner is unavailable.")
        val found = CompletableDeferred<BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                main.post { found.complete(result.device) }
            }
            override fun onScanFailed(errorCode: Int) {
                main.post { found.completeExceptionally(IllegalStateException("Bluetooth scan failed ($errorCode).")) }
            }
        }
        val filter = ScanFilter.Builder().setDeviceName("I_RW").build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
            found.await()
        } finally {
            scanner.stopScan(callback)
        }
    }

    private inner class GattSession {
        private val assembler = RowerProtocol.MessageAssembler()
        private val writes = Mutex()
        private val connected = CompletableDeferred<Unit>()
        private val disconnected = CompletableDeferred<String>()
        private var pending: CompletableDeferred<Unit>? = null
        private var pendingType: String? = null
        private var gatt: BluetoothGatt? = null
        private var writer: BluetoothGattCharacteristic? = null
        private var lastNotification = 0L
        private var version = 0L
        private val sessionUpdates = MutableStateFlow(0L)

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                main.post {
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        connected.complete(Unit)
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                        val message = "Rower disconnected (Bluetooth status $status)."
                        disconnected.complete(message)
                        connected.completeExceptionally(IllegalStateException(message))
                        pending?.completeExceptionally(IllegalStateException(message))
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
                complete("services", status)

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
                complete("descriptor", status)

            @Deprecated("Deprecated in Android 13")
            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
                complete("write", status)

            @Deprecated("Deprecated in Android 13")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) receive(characteristic.value?.clone() ?: return)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
            ) {
                receive(value.clone())
            }
        }

        private fun complete(type: String, status: Int) {
            main.post {
                if (pendingType == type) {
                    if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(Unit)
                    else pending?.completeExceptionally(IllegalStateException("Bluetooth $type failed ($status)."))
                }
            }
        }

        private fun receive(frame: ByteArray) {
            main.post {
                if (session !== this) return@post
                lastNotification = SystemClock.elapsedRealtime()
                if (frame.size != 20) {
                    assembler.reset()
                    mutableState.value = mutableState.value.copy(message = "Ignored an invalid Bluetooth notification.")
                    return@post
                }
                RowerProtocol.decodeMotionFrame(frame)?.let(::updateMetrics)
                val result = assembler.feed(frame) ?: return@post
                if (!result.checksumOk) {
                    mutableState.value = mutableState.value.copy(message = "Ignored a message with an invalid checksum.")
                    return@post
                }
                RowerProtocol.decodeMessage(result.body)?.let(::updateMetrics)
            }
        }

        private fun updateMetrics(values: Map<String, Any?>) {
            onMetrics(values)
            val now = SystemClock.elapsedRealtime()
            mutableState.value = mutableState.value.copy(
                readings = mutableState.value.readings + values.mapValues { Reading(it.value, now) }
            )
            if ("sessionState" in values) {
                version++
                sessionUpdates.value = version
            }
        }

        suspend fun open(device: BluetoothDevice) {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: error("Could not start the Bluetooth connection.")
            if (withTimeoutOrNull(15_000) { connected.await(); true } == null) {
                error("Bluetooth connection timed out.")
            }
            val connection = requireNotNull(gatt)
            operation("services") { connection.discoverServices() }
            val service = connection.getService(RowerProtocol.service)
                ?: error("Rower service not found.")
            writer = service.getCharacteristic(RowerProtocol.write)
                ?: error("Rower write characteristic not found.")
            val notify = service.getCharacteristic(RowerProtocol.notify)
                ?: error("Rower notification characteristic not found.")
            if (!connection.setCharacteristicNotification(notify, true)) {
                error("Could not enable rower notifications.")
            }
            val descriptor = notify.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                ?: error("Rower notification descriptor not found.")
            operation("descriptor") {
                if (Build.VERSION.SDK_INT >= 33) {
                    connection.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    connection.writeDescriptor(descriptor)
                }
            }
        }

        private suspend fun operation(type: String, start: () -> Boolean) {
            check(!disconnected.isCompleted) { "The rower disconnected." }
            val result = CompletableDeferred<Unit>()
            pending = result
            pendingType = type
            try {
                check(start()) { "Could not start Bluetooth $type." }
                if (withTimeoutOrNull(5_000) { result.await(); true } == null) {
                    error("Bluetooth $type timed out.")
                }
            } finally {
                pending = null
                pendingType = null
            }
        }

        private suspend fun send(frames: List<ByteArray>) = writes.withLock {
            val connection = requireNotNull(gatt)
            val characteristic = requireNotNull(writer)
            for (frame in frames) {
                operation("write") {
                    if (Build.VERSION.SDK_INT >= 33) {
                        connection.writeCharacteristic(
                            characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        characteristic.value = frame
                        @Suppress("DEPRECATION")
                        connection.writeCharacteristic(characteristic)
                    }
                }
            }
        }

        suspend fun initialize() {
            for (frames in RowerProtocol.setup) {
                send(frames)
                val deadline = SystemClock.elapsedRealtime() + 1_500
                delay(150)
                while (SystemClock.elapsedRealtime() < deadline &&
                    SystemClock.elapsedRealtime() - lastNotification < 150
                ) delay(50)
            }
        }

        suspend fun poll() {
            var index = 0
            while (scope.isActive) {
                if (disconnected.isCompleted) error(disconnected.await())
                send(RowerProtocol.poll[index++ % RowerProtocol.poll.size])
                delay(600)
            }
        }

        suspend fun command(messages: List<List<ByteArray>>, expected: Int, required: Boolean = true) {
            val before = version
            send(messages.flatten())
            val confirmed = withTimeoutOrNull(5_000) {
                sessionUpdates.first { update ->
                    update > before && mutableState.value.readings["sessionState"]?.value == expected
                }
            }
            if (confirmed == null && required) {
                error("The rower did not confirm the state change. Check its console before trying again.")
            }
        }

        fun close() {
            gatt?.disconnect()
            gatt?.close()
            gatt = null
        }
    }
}
