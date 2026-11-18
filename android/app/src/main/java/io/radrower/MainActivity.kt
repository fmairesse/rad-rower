package io.radrower

import android.bluetooth.BluetoothAdapter
import android.content.Intent
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import io.radrower.ui.theme.RadRowerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : ComponentActivity() {
    private lateinit var rower: RowerConnection
    private var foreground = false
    private var permissionRequested = false
    private var permissionGranted by mutableStateOf(false)
    private var bluetoothEnabled by mutableStateOf(false)

    private data class PendingHealthExport(
        val workout: Workout,
        val samples: List<WorkoutSample>,
    )

    private var pendingHealthExport: PendingHealthExport? = null
    private var exportingWorkoutId by mutableStateOf<String?>(null)
    private var healthExportMessage by mutableStateOf<String?>(null)
    private var healthExportMessageIsError by mutableStateOf(false)

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshAvailability()
            if (foreground && permissionGranted && bluetoothEnabled) rower.connect()
        }
    private val healthConnectPermissions =
        registerForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { grantedPermissions ->
            val export = pendingHealthExport
            pendingHealthExport = null
            if (export != null) {
                if (grantedPermissions.containsAll(HealthConnectExport.permissionsFor(export.workout, export.samples))) {
                    writeWorkoutToHealthConnect(export.workout, export.samples)
                } else {
                    exportingWorkoutId = null
                    healthExportMessage = "Health Connect permission was not granted."
                    healthExportMessageIsError = true
                }
            }
        }
    private val enableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
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
                app.database
                    .workouts()
                    .observeWorkouts()
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
                    exportMessage = healthExportMessage,
                    exportMessageIsError = healthExportMessageIsError,
                    exportingWorkoutId = exportingWorkoutId,
                    onExport = ::beginHealthConnectExport,
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
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:$packageName".toUri(),
                ),
            )
        } else {
            permissionRequested = true
            permissions.launch(bluetoothPermissions())
        }
    }

    private fun beginHealthConnectExport(workout: Workout) {
        if (!HealthConnectExport.isEligible(workout)) {
            healthExportMessage = "Only completed, non-partial workouts can be exported."
            healthExportMessageIsError = true
            return
        }
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                healthExportMessage = "Health Connect is not available on this device."
                healthExportMessageIsError = true
                return
            }

            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                healthExportMessage = "Install or update Health Connect, then try again."
                healthExportMessageIsError = true
                return
            }
        }
        exportingWorkoutId = workout.id
        healthExportMessage = null
        lifecycleScope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(applicationContext)
                val samples = (application as RadrowerApplication).database.workouts().samples(workout.id)
                val export = PendingHealthExport(workout, samples)
                val neededPermissions = HealthConnectExport.permissionsFor(workout, samples)
                if (client.permissionController.getGrantedPermissions().containsAll(neededPermissions)) {
                    writeWorkoutToHealthConnect(workout, samples, client)
                } else {
                    pendingHealthExport = export
                    healthConnectPermissions.launch(neededPermissions)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                finishHealthConnectExport("Unable to access Health Connect: ${error.message ?: "request failed"}", true)
            }
        }
    }

    private fun writeWorkoutToHealthConnect(
        workout: Workout,
        samples: List<WorkoutSample>,
        client: HealthConnectClient? = null,
    ) {
        lifecycleScope.launch {
            try {
                val healthConnectClient = client ?: HealthConnectClient.getOrCreate(applicationContext)
                HealthConnectExport.export(healthConnectClient, workout, samples)
                finishHealthConnectExport("Workout exported to Health Connect.", false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                finishHealthConnectExport("Unable to export workout: ${error.message ?: "request failed"}", true)
            }
        }
    }

    private fun finishHealthConnectExport(
        message: String,
        isError: Boolean,
    ) {
        exportingWorkoutId = null
        healthExportMessage = message
        healthExportMessageIsError = isError
    }
}

internal suspend fun keepScreenOnWhileActive(
    window: Window,
    states: Flow<RowerUiState>,
) {
    states.collect { state ->
        if (state.connection == ConnectionState.STREAMING && state.readings["sessionState"]?.value == 2) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private data class MetricDefinition(
    val key: String,
    val label: String,
    val unit: String,
    val note: String = "",
)

private val metrics =
    listOf(
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
            delay(1_000.milliseconds)
        }
    }
    val sessionState = state.sessionState(now)
    val ready = state.connection == ConnectionState.STREAMING && !state.controlling
    val status =
        when {
            !bluetoothAvailable -> {
                "Bluetooth is not available on this device."
            }

            !permissionGranted -> {
                "Bluetooth permission is required."
            }

            !bluetoothEnabled -> {
                "Bluetooth is off."
            }

            else -> {
                when (state.connection) {
                    ConnectionState.DISCONNECTED -> "Disconnected"
                    ConnectionState.SCANNING -> "Searching for I_RW…"
                    ConnectionState.CONNECTING -> "Connecting…"
                    ConnectionState.INITIALIZING -> "Initializing the rower…"
                    ConnectionState.STREAMING -> "Connected to I_RW"
                }
            }
        }
    Scaffold { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("RADROWER", style = MaterialTheme.typography.headlineMedium)
            Text("ProForm Sport RL", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(status, style = MaterialTheme.typography.titleMedium)
                    Text("Keep the app in the foreground. The rower must be on and disconnected from other apps.")
                    if (!permissionGranted && bluetoothAvailable) {
                        Button(onClick = onPermission) { Text("Allow Bluetooth") }
                    } else if (!bluetoothEnabled && bluetoothAvailable) {
                        Button(onClick = onEnableBluetooth) { Text("Turn on Bluetooth") }
                    } else if (state.connection == ConnectionState.DISCONNECTED && bluetoothAvailable) {
                        Button(onClick = onRetry) { Text("Retry connection") }
                    }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            if (state.connection == ConnectionState.STREAMING) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Session", style = MaterialTheme.typography.titleLarge)
                        Text(
                            when (sessionState) {
                                SessionState.IDLE -> "Idle"
                                SessionState.IN_PROGRESS -> "In progress"
                                SessionState.PAUSED -> "Paused"
                                SessionState.FINISHED -> "Finished"
                                null -> "State unavailable"
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onToggle,
                                enabled =
                                    ready && sessionState in listOf(SessionState.IDLE, SessionState.IN_PROGRESS, SessionState.PAUSED),
                            ) {
                                Text(
                                    when (sessionState) {
                                        SessionState.IN_PROGRESS -> "Pause"
                                        SessionState.PAUSED -> "Resume"
                                        else -> "Start"
                                    },
                                )
                            }
                            OutlinedButton(
                                onClick = onStop,
                                enabled =
                                    ready && sessionState in listOf(SessionState.IN_PROGRESS, SessionState.PAUSED),
                            ) {
                                Text("Stop")
                            }
                        }
                        if (state.controlling) Text("Waiting for the rower to confirm…")
                    }
                }
            }
            if (sessionState != SessionState.IN_PROGRESS) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Workouts", style = MaterialTheme.typography.titleLarge)
                        storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (workouts != null) {
                            Text("${workouts.size} saved on this device")
                        } else if (storageError == null) {
                            Text("Loading workouts…")
                        }
                        OutlinedButton(onClick = onHistory) { Text("View workout history") }
                    }
                }
            }
            if (sessionState == SessionState.IN_PROGRESS) {
                Text("Live metrics", style = MaterialTheme.typography.titleLarge)
                metrics.forEach { definition ->
                    val reading = state.readings[definition.key]
                    val stale =
                        reading != null &&
                            (state.connection != ConnectionState.STREAMING || now - reading.receivedAt > 5_000)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(definition.label, style = MaterialTheme.typography.labelLarge)
                            Text(
                                "${formatMetric(definition.key, reading?.value)} ${definition.unit}",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                when {
                                    reading == null -> "Waiting for data"
                                    stale -> "Last value · not up to date"
                                    reading.value == null && definition.key == "resistance" -> "Level not reported by the rower"
                                    reading.value == null -> "Value unavailable"
                                    definition.note.isNotEmpty() -> definition.note
                                    else -> "Data from the rower"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Workout not started", showSystemUi = true)
@Composable
private fun RowerScreenIdlePreview() {
    val now = SystemClock.elapsedRealtime()
    RadRowerTheme {
        RowerScreen(
            state =
                RowerUiState(
                    connection = ConnectionState.STREAMING,
                    readings = mapOf("sessionState" to Reading(1, now)),
                ),
            permissionGranted = true,
            bluetoothEnabled = true,
            bluetoothAvailable = true,
            onPermission = {},
            onEnableBluetooth = {},
            onRetry = {},
            onToggle = {},
            onStop = {},
        )
    }
}

@Preview(name = "Workout in progress", showSystemUi = true)
@Composable
private fun RowerScreenActiveWorkoutPreview() {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000.milliseconds)
        }
    }
    RadRowerTheme {
        RowerScreen(
            state =
                RowerUiState(
                    connection = ConnectionState.STREAMING,
                    readings =
                        mapOf(
                            "sessionState" to Reading(2, now),
                            "watts" to Reading(142, now),
                            "pace" to Reading(132, now),
                            "averagePace" to Reading(145, now),
                            "cadence" to Reading(26, now),
                            "distance" to Reading(1_250, now),
                            "strokes" to Reading(68, now),
                            "speed" to Reading(13.6, now),
                            "resistance" to Reading(12, now),
                            "elapsed" to Reading(310, now),
                            "paused" to Reading(0, now),
                            "calories" to Reading(42, now),
                            "caloriesPerHour" to Reading(487, now),
                            "averageWatts" to Reading(136, now),
                        ),
                ),
            permissionGranted = true,
            bluetoothEnabled = true,
            bluetoothAvailable = true,
            onPermission = {},
            onEnableBluetooth = {},
            onRetry = {},
            onToggle = {},
            onStop = {},
        )
    }
}

internal fun formatMetric(
    key: String,
    value: Any?,
): String {
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
