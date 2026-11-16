package io.radrower

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.radrower.ui.theme.RadRowerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.text.NumberFormat

class MainActivity : ComponentActivity() {
    private lateinit var rower: RowerConnection
    private var foreground = false
    private var permissionRequested = false
    private var permissionGranted by mutableStateOf(false)
    private var bluetoothEnabled by mutableStateOf(false)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshAvailability()
        if (foreground && permissionGranted && bluetoothEnabled) rower.connect()
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshAvailability()
        if (foreground && permissionGranted && bluetoothEnabled) rower.connect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionRequested = savedInstanceState?.getBoolean("permissionRequested") ?: false
        val app = application as RadrowerApplication
        val recorder = app.recorder
        rower = RowerConnection(applicationContext, lifecycleScope, recorder::record, recorder::disconnect)
        enableEdgeToEdge()
        lifecycleScope.launch {
            keepScreenOnWhileActive(window, rower.state)
        }
        setContent {
            val state by rower.state.collectAsState()
            val storageError by recorder.error.collectAsState()
            var workouts by remember { mutableStateOf<List<Workout>?>(null) }
            var readError by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(app) {
                app.database.workouts().observeWorkouts()
                    .catch { readError = "Unable to read workouts: ${it.message ?: "database error"}" }
                    .collect { workouts = it }
            }
            RadRowerTheme {
                RadrowerScreens(
                    state = state,
                    permissionGranted = permissionGranted,
                    bluetoothEnabled = bluetoothEnabled,
                    bluetoothAvailable = rower.bluetoothAvailable(),
                    onPermission = ::requestPermissions,
                    onEnableBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                    onRetry = {
                        refreshAvailability()
                        if (permissionGranted && bluetoothEnabled) rower.connect()
                    },
                    onToggle = { rower.control(false) },
                    onStop = { rower.control(true) },
                    workouts = workouts,
                    storageError = storageError ?: readError,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        refreshAvailability()
        if (permissionGranted && bluetoothEnabled) {
            rower.connect()
        } else if (!permissionGranted && !permissionRequested) {
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAvailability()
        if (permissionGranted && bluetoothEnabled) rower.connect()
    }

    override fun onStop() {
        foreground = false
        rower.disconnect()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permissionRequested", permissionRequested)
        super.onSaveInstanceState(outState)
    }

    private fun refreshAvailability() {
        permissionGranted = hasBluetoothPermissions(this)
        bluetoothEnabled = permissionGranted && rower.bluetoothEnabled()
    }

    private fun requestPermissions() {
        if (permissionRequested && bluetoothPermissions().none { shouldShowRequestPermissionRationale(it) }) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            permissionRequested = true
            permissions.launch(bluetoothPermissions())
        }
    }
}

internal suspend fun keepScreenOnWhileActive(window: Window, states: Flow<RowerUiState>) {
    states.collect { state ->
        if (state.connection == ConnectionState.STREAMING && state.readings["sessionState"]?.value == 2) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private data class MetricDefinition(val key: String, val label: String, val unit: String, val note: String = "")

private val metrics = listOf(
    MetricDefinition("watts", "Power", "W"),
    MetricDefinition("pace", "Pace", "/ 500 m"),
    MetricDefinition("averagePace", "Average pace", "/ 500 m", "Interpretation not yet confirmed"),
    MetricDefinition("cadence", "Cadence", "strokes/min"),
    MetricDefinition("distance", "Distance", "m"),
    MetricDefinition("strokes", "Stroke count", "strokes"),
    MetricDefinition("speed", "Speed", "km/h", "Calculated from pace"),
    MetricDefinition("resistance", "Resistance", "/ 24"),
    MetricDefinition("elapsed", "Elapsed time", ""),
    MetricDefinition("paused", "Paused time", "", "Interpretation not yet confirmed"),
    MetricDefinition("calories", "Calories", "kcal"),
    MetricDefinition("caloriesPerHour", "Estimated burn", "kcal/h", "Estimated from power"),
    MetricDefinition("averageWatts", "Average power", "W", "Interpretation not yet confirmed"),
)

@Composable
internal fun RowerScreen(
    state: RowerUiState,
    permissionGranted: Boolean,
    bluetoothEnabled: Boolean,
    bluetoothAvailable: Boolean,
    onPermission: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onRetry: () -> Unit,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    workouts: List<Workout>? = emptyList(),
    storageError: String? = null,
    onHistory: () -> Unit = {},
) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val sessionState = state.sessionState(now)
    val ready = state.connection == ConnectionState.STREAMING && !state.controlling
    val status = when {
        !bluetoothAvailable -> "Bluetooth is not available on this device."
        !permissionGranted -> "Bluetooth permission is required."
        !bluetoothEnabled -> "Bluetooth is off."
        else -> when (state.connection) {
            ConnectionState.DISCONNECTED -> "Disconnected"
            ConnectionState.SCANNING -> "Searching for I_RW…"
            ConnectionState.CONNECTING -> "Connecting…"
            ConnectionState.INITIALIZING -> "Initializing the rower…"
            ConnectionState.STREAMING -> "Connected to I_RW"
        }
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("RADROWER", style = MaterialTheme.typography.headlineMedium)
            Text("ProForm Sport RL", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(status, style = MaterialTheme.typography.titleMedium)
                    Text("Keep the app in the foreground. The rower must be on and disconnected from other apps.")
                    if (!permissionGranted && bluetoothAvailable) Button(onClick = onPermission) { Text("Allow Bluetooth") }
                    else if (!bluetoothEnabled && bluetoothAvailable) Button(onClick = onEnableBluetooth) { Text("Turn on Bluetooth") }
                    else if (state.connection == ConnectionState.DISCONNECTED && bluetoothAvailable) {
                        Button(onClick = onRetry) { Text("Retry connection") }
                    }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Session", style = MaterialTheme.typography.titleLarge)
                    Text(when (sessionState) {
                        1 -> "Idle"
                        2 -> "In progress"
                        3 -> "Paused"
                        4 -> "Finished"
                        else -> "State unavailable"
                    })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onToggle, enabled = ready && sessionState in listOf(1, 2, 3)) {
                            Text(when (sessionState) { 2 -> "Pause"; 3 -> "Resume"; else -> "Start" })
                        }
                        OutlinedButton(onClick = onStop, enabled = ready && sessionState in listOf(2, 3)) {
                            Text("Stop")
                        }
                    }
                    if (state.controlling) Text("Waiting for the rower to confirm…")
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Workouts", style = MaterialTheme.typography.titleLarge)
                    storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (workouts != null) Text("${workouts.size} saved on this device")
                    else if (storageError == null) Text("Loading workouts…")
                    OutlinedButton(onClick = onHistory) { Text("View workout history") }
                }
            }
            Text("Live metrics", style = MaterialTheme.typography.titleLarge)
            metrics.forEach { definition ->
                val reading = state.readings[definition.key]
                val stale = reading != null &&
                    (state.connection != ConnectionState.STREAMING || now - reading.receivedAt > 5_000)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(definition.label, style = MaterialTheme.typography.labelLarge)
                        Text("${formatMetric(definition.key, reading?.value)} ${definition.unit}",
                            style = MaterialTheme.typography.headlineSmall)
                        Text(when {
                            reading == null -> "Waiting for data"
                            stale -> "Last value · not up to date"
                            reading.value == null && definition.key == "resistance" -> "Level not reported by the rower"
                            reading.value == null -> "Value unavailable"
                            definition.note.isNotEmpty() -> definition.note
                            else -> "Data from the rower"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

internal fun formatMetric(key: String, value: Any?): String {
    if (value == null) return "—"
    if (value is Number) {
        if (key in listOf("pace", "averagePace", "elapsed", "paused")) {
            val seconds = value.toInt()
            return "%d:%02d".format(seconds / 60, seconds % 60)
        }
        return NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(value)
    }
    return value.toString()
}
