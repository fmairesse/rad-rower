package io.radrower

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.kilocalories
import androidx.health.connect.client.units.meters
import java.time.Instant
import java.time.ZoneOffset

internal object HealthConnectExport {
    fun isEligible(workout: Workout): Boolean =
        workout.status == "completed" &&
            !workout.partial &&
            workout.endedAt != null &&
            workout.endedAt > workout.startedAt

    fun permissionsFor(workout: Workout): Set<String> = buildSet {
        add(HealthPermission.getWritePermission(ExerciseSessionRecord::class))
        if (workout.distance.isPositiveFinite()) {
            add(HealthPermission.getWritePermission(DistanceRecord::class))
        }
        if (workout.calories.isPositiveFinite()) {
            add(HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class))
        }
    }

    fun records(workout: Workout): List<Record> {
        require(isEligible(workout)) {
            "Only completed, non-partial workouts with a valid time range can be exported."
        }
        val start = Instant.ofEpochMilli(workout.startedAt)
        val end = Instant.ofEpochMilli(requireNotNull(workout.endedAt))
        val startOffset = ZoneOffset.ofTotalSeconds(workout.startedOffsetSeconds)
        val endOffset = ZoneOffset.ofTotalSeconds(
            workout.endedOffsetSeconds ?: workout.lastSampleOffsetSeconds,
        )
        val sessionId = "radrower:${workout.id}:exercise"
        val records = mutableListOf<Record>(
            ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
                title = "Indoor rowing",
                metadata = metadata(sessionId),
            ),
        )
        workout.distance.takeIf { it.isPositiveFinite() }?.let { distance ->
            records += DistanceRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                distance = distance.meters,
                metadata = metadata("radrower:${workout.id}:distance"),
            )
        }
        workout.calories.takeIf { it.isPositiveFinite() }?.let { calories ->
            records += ActiveCaloriesBurnedRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                energy = calories.kilocalories,
                metadata = metadata("radrower:${workout.id}:active-calories"),
            )
        }
        return records
    }

    suspend fun export(client: HealthConnectClient, workout: Workout) {
        val requiredPermissions = permissionsFor(workout)
        val grantedPermissions = client.permissionController.getGrantedPermissions()
        check(grantedPermissions.containsAll(requiredPermissions)) {
            "Health Connect permissions are missing. Grant access and try again."
        }
        client.insertRecords(records(workout))
    }

    private fun metadata(clientRecordId: String) = Metadata.activelyRecorded(
        device = Device(type = Device.TYPE_PHONE),
        clientRecordId = clientRecordId,
        clientRecordVersion = 1,
    )

    private fun Double?.isPositiveFinite(): Boolean = this != null && isFinite() && this > 0
}
