package io.radrower

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun RadrowerScreens(
    state: RowerUiState,
    permissionGranted: Boolean,
    bluetoothEnabled: Boolean,
    bluetoothAvailable: Boolean,
    onPermission: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onRetry: () -> Unit,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    workouts: List<Workout>?,
    storageError: String?,
    exportMessage: String? = null,
) {
    var showHistory by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showHistory) { showHistory = false }
    if (showHistory) {
        WorkoutHistoryScreen(
            workouts, storageError, { showHistory = false },
            exportMessage,
        )
    } else {
        RowerScreen(
            state, permissionGranted, bluetoothEnabled, bluetoothAvailable,
            onPermission, onEnableBluetooth, onRetry, onToggle, onStop,
            workouts, storageError, onHistory = { showHistory = true },
        )
    }
}

@Composable
internal fun WorkoutHistoryScreen(
    workouts: List<Workout>?,
    storageError: String?,
    onBack: () -> Unit,
    exportMessage: String? = null,
) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .testTag("workoutHistory"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Back to dashboard") }
                    Text("Workout history", style = MaterialTheme.typography.headlineMedium)
                    storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    exportMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    when {
                        workouts == null && storageError == null -> Text("Loading workouts…")
                        workouts != null && workouts.isEmpty() && storageError == null -> Text("No workouts saved yet.")
                    }
                }
            }
            if (workouts != null) {
                items(workouts, key = { it.id }) { workout ->
                    WorkoutHistoryItem(workout)
                }
            }
        }
    }
}

@Composable
private fun WorkoutHistoryItem(workout: Workout) {
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(workout.startedAtEpochMs))
    val status = when (workout.status) {
        "recording" -> "In progress"
        "completed" -> "Completed"
        "interrupted" -> "Interrupted"
        else -> workout.status
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(date, style = MaterialTheme.typography.titleMedium)
            Text(if (workout.partial) "$status · partial" else status)
            Text("Duration: ${formatWorkoutDuration(workout.elapsedS)}")
            Text("Distance: ${workout.distanceM?.let { "${formatMetric("distanceM", it)} m" } ?: "—"}")
            Text("Calories: ${workout.caloriesKcal?.let { "${formatMetric("caloriesKcal", it)} kcal" } ?: "—"}")
            if (HealthConnectExport.isEligible(workout)) {
                Text(if (workout.exported) "Health Connect: exported" else "Health Connect: pending export")
            } else {
                Text("Health Connect: not eligible")
            }
        }
    }
}

internal fun formatWorkoutDuration(seconds: Double?): String {
    if (seconds == null) return "—"
    val total = seconds.toLong()
    return if (total >= 3600) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
    }
}
