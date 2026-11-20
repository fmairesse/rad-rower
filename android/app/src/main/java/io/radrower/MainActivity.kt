package io.radrower

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.Lifecycle
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
    private var bluetoothPermissionPending = false
    private var startupHealthChecked = false
    private var checkingStartupHealth = false
    private var permissionGranted by mutableStateOf(false)
    private var bluetoothEnabled by mutableStateOf(false)

    private data class PendingHealthExport(
        val workout: Workout,
        val samples: List<WorkoutSample>,
        val segments: List<Segment>,
    )

    private var pendingHealthExport: PendingHealthExport? = null
    private var exportingWorkoutId by mutableStateOf<String?>(null)
    private var healthExportMessage by mutableStateOf<String?>(null)
    private var healthExportMessageIsError by mutableStateOf(false)

    private val startupHealthPermissions: Set<String>
        get() = if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HealthConnectExport.allWritePermissions + HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE
        } else {
            HealthConnectExport.allWritePermissions
        }

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            bluetoothPermissionPending = false
            refreshAvailability()
            if (foreground && permissionGranted && bluetoothEnabled) rower.connect()
            requestStartupHealthPermissions()
        }
    private val healthConnectPermissions =
        registerForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { grantedPermissions ->
            val export = pendingHealthExport
            pendingHealthExport = null
            if (export != null) {
                if (grantedPermissions.containsAll(HealthConnectExport.permissionsFor(export.workout, export.samples))) {
                    writeWorkoutToHealthConnect(export.workout, export.samples, export.segments)
                } else {
                    exportingWorkoutId = null
                    healthExportMessage = "Health Connect permission was not granted."
                    healthExportMessageIsError = true
                }
            } else if (!grantedPermissions.containsAll(startupHealthPermissions)) {
                healthExportMessage = "Some Health Connect permissions were not granted. Missing workout permissions can be requested when exporting."
                healthExportMessageIsError = true
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
        bluetoothPermissionPending = savedInstanceState?.getBoolean("bluetoothPermissionPending") ?: false
        startupHealthChecked = savedInstanceState?.getBoolean("startupHealthChecked") ?: false
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
        requestStartupHealthPermissions()
    }

    override fun onStop() {
        foreground = false
        rower.disconnect()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permissionRequested", permissionRequested)
        outState.putBoolean("bluetoothPermissionPending", bluetoothPermissionPending)
        outState.putBoolean("startupHealthChecked", startupHealthChecked)
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
            bluetoothPermissionPending = true
            permissions.launch(bluetoothPermissions())
        }
    }

    private fun requestStartupHealthPermissions() {
        if (startupHealthChecked || checkingStartupHealth || bluetoothPermissionPending ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            startupHealthChecked = true
            return
        }
        checkingStartupHealth = true
        lifecycleScope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(applicationContext)
                val granted = client.permissionController.getGrantedPermissions()
                if (startupHealthChecked || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                startupHealthChecked = true
                if (!granted.containsAll(startupHealthPermissions)) {
                    healthConnectPermissions.launch(startupHealthPermissions)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                startupHealthChecked = true
                healthExportMessage = "Unable to access Health Connect: ${error.message ?: "request failed"}"
                healthExportMessageIsError = true
            } finally {
                checkingStartupHealth = false
            }
        }
    }

    private fun beginHealthConnectExport(workout: Workout) {
        startupHealthChecked = true
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
                val dao = (application as RadrowerApplication).database.workouts()
                val samples = dao.samples(workout.id)
                val segments = dao.segments(workout.id)
                val export = PendingHealthExport(workout, samples, segments)
                val neededPermissions = HealthConnectExport.permissionsFor(workout, samples)
                if (client.permissionController.getGrantedPermissions().containsAll(neededPermissions)) {
                    writeWorkoutToHealthConnect(workout, samples, segments, client)
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
        segments: List<Segment>,
        client: HealthConnectClient? = null,
    ) {
        lifecycleScope.launch {
            try {
                val healthConnectClient = client ?: HealthConnectClient.getOrCreate(applicationContext)
                val result = HealthConnectExport.export(healthConnectClient, workout, samples, segments)
                val message = if (result.lapsUnavailable) {
                    "Workout exported to Health Connect, but laps could not be calculated from the saved distance samples."
                } else {
                    "Workout exported to Health Connect."
                }
                finishHealthConnectExport(message, false)
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
        MetricDefinition("powerW", "Power", "W"),
        MetricDefinition("elapsedS", "Elapsed time", ""),
        MetricDefinition("distanceM", "Distance", "m"),
        MetricDefinition("paceSPer500M", "Pace", "/ 500 m"),
        MetricDefinition("averagePace", "Average pace", "/ 500 m", "Unvalidated metric"),
        MetricDefinition("cadenceSpm", "Cadence", "str/min"),
        MetricDefinition("strokesCount", "Stroke count", "strokes"),
        MetricDefinition("speedKmh", "Speed", "km/h", "Calculated from pace"),
        MetricDefinition("resistanceLevel", "Resistance", "/ 24"),
        MetricDefinition("caloriesKcal", "Calories", "kcal"),
        MetricDefinition("paused", "Paused time", "", "Unvalidated metric"),
        MetricDefinition("caloriesPerHour", "Estimated burn", "kcal/h", "Unvalidated metric"),
        MetricDefinition("averageWatts", "Average power", "W", "Unvalidated metric"),
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
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val spacing = 12.dp
            val availableWidth = maxWidth - 32.dp
            val columns = maxOf(2, ((availableWidth + spacing) / (160.dp + spacing)).toInt())
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().testTag("dashboardGrid"),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("RADROWER", style = MaterialTheme.typography.headlineMedium)
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("ProForm Sport RL", style = MaterialTheme.typography.titleMedium)
                }
                if (state.connection != ConnectionState.STREAMING || sessionState != SessionState.IN_PROGRESS) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
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
                    }
                }
                if (state.connection == ConnectionState.STREAMING) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
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
                                            ready &&
                                                sessionState in listOf(SessionState.IDLE, SessionState.IN_PROGRESS, SessionState.PAUSED),
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
                                if (sessionState == SessionState.IN_PROGRESS) {
                                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
                if (sessionState != SessionState.IN_PROGRESS) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
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
                }
                if (sessionState == SessionState.IN_PROGRESS) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Live metrics", style = MaterialTheme.typography.titleLarge)
                    }
                    items(metrics, key = { it.key }) { definition ->
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
                                        reading.value == null && definition.key == "resistanceLevel" ->
                                            "Level not reported by the rower"
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
                            "powerW" to Reading(142, now),
                            "paceSPer500M" to Reading(132, now),
                            "averagePace" to Reading(145, now),
                            "cadenceSpm" to Reading(26, now),
                            "distanceM" to Reading(1_250, now),
                            "strokesCount" to Reading(68, now),
                            "speedKmh" to Reading(13.6, now),
                            "resistanceLevel" to Reading(12, now),
                            "elapsedS" to Reading(310, now),
                            "caloriesKcal" to Reading(42, now),
                            "paused" to Reading(0, now),
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
        if (key in listOf("paceSPer500M", "averagePace", "elapsedS", "paused")) {
            val seconds = value.toInt()
            return "%d:%02d".format(seconds / 60, seconds % 60)
        }
        return NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(value)
    }
    return value.toString()
}
