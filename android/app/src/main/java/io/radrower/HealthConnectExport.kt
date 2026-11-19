package io.radrower

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.kilocalories
import androidx.health.connect.client.units.meters
import androidx.health.connect.client.units.metersPerSecond
import androidx.health.connect.client.units.watts
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

internal object HealthConnectExport {
    // Health Connect guidance: at most 1000 records per write request.
    private const val MAX_RECORDS_PER_INSERT = 1_000

    // Bumped when the distance record layout changes so that re-exports overwrite older records.
    private const val DISTANCE_RECORD_VERSION = 6L

    fun isEligible(workout: Workout): Boolean =
        workout.status == "completed" &&
            !workout.partial &&
            workout.endedAt != null &&
            workout.endedAt > workout.startedAt

    fun permissionsFor(workout: Workout, samples: List<WorkoutSample> = emptyList()): Set<String> = buildSet {
        add(HealthPermission.getWritePermission(ExerciseSessionRecord::class))
        if (workout.distance.isPositiveFinite()) {
            add(HealthPermission.getWritePermission(DistanceRecord::class))
        }
        if (workout.calories.isPositiveFinite()) {
            add(HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class))
        }
        if (activeMetricSamples(workout, samples, "watts").isNotEmpty()) {
            add(HealthPermission.getWritePermission(PowerRecord::class))
        }
        if (activeMetricSamples(workout, samples, "speed").isNotEmpty()) {
            add(HealthPermission.getWritePermission(SpeedRecord::class))
        }
    }

    fun records(workout: Workout, samples: List<WorkoutSample> = emptyList()): List<Record> {
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
                metadata = metadata("radrower:${workout.id}:distance", clientRecordVersion = DISTANCE_RECORD_VERSION),
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
        val powerSamples = activeMetricSamples(workout, samples, "watts").map { (timestamp, value) ->
            PowerRecord.Sample(Instant.ofEpochMilli(timestamp), value.watts)
        }
        if (powerSamples.isNotEmpty()) {
            records += PowerRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                samples = powerSamples,
                metadata = metadata("radrower:${workout.id}:power"),
            )
        }
        val speedSamples = activeMetricSamples(workout, samples, "speed").map { (timestamp, value) ->
            SpeedRecord.Sample(Instant.ofEpochMilli(timestamp), (value / 3.6).metersPerSecond)
        }
        if (speedSamples.isNotEmpty()) {
            records += SpeedRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                samples = speedSamples,
                metadata = metadata("radrower:${workout.id}:speed"),
            )
        }
        return records
    }

    suspend fun export(
        client: HealthConnectClient,
        workout: Workout,
        samples: List<WorkoutSample> = emptyList(),
    ) {
        val requiredPermissions = permissionsFor(workout, samples)
        val grantedPermissions = client.permissionController.getGrantedPermissions()
        check(grantedPermissions.containsAll(requiredPermissions)) {
            "Health Connect permissions are missing. Grant access and try again."
        }
        // Retries are idempotent thanks to stable client record IDs, so chunking is safe.
        records(workout, samples).chunked(MAX_RECORDS_PER_INSERT).forEach { client.insertRecords(it) }
    }

    private fun activeMetricSamples(workout: Workout, samples: List<WorkoutSample>, key: String): Map<Long, Double> {
        val startAt = workout.startedAt
        val endAt = workout.endedAt ?: return emptyMap()
        var sessionState: Int? = null
        val activeSamples = sortedMapOf<Long, Double>()
        for ((_, _, timestamp, _, metricsJson) in samples.sortedBy { it.sequence }) {
            val metrics = JSONObject(metricsJson)
            if (metrics.has("sessionState") && !metrics.isNull("sessionState")) {
                sessionState = metrics.optInt("sessionState", -1)
            }
            if (sessionState != 2 || timestamp !in startAt..endAt) continue
            val value = (metrics.opt(key) as? Number)?.toDouble() ?: continue
            if (value.isFinite() && value >= 0) {
                activeSamples[timestamp] = value
            }
        }
        return activeSamples
    }

    private fun metadata(clientRecordId: String, clientRecordVersion: Long = 1) = Metadata.activelyRecorded(
        device = Device(type = Device.TYPE_PHONE),
        clientRecordId = clientRecordId,
        clientRecordVersion = clientRecordVersion,
    )

    private fun Double?.isPositiveFinite(): Boolean = this != null && isFinite() && this > 0
}
