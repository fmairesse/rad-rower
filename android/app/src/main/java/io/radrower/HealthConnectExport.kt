package io.radrower

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.kilocalories
import androidx.health.connect.client.units.meters
import androidx.health.connect.client.units.watts
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

internal object HealthConnectExport {
    fun isEligible(workout: Workout): Boolean =
        workout.status == "completed" &&
            !workout.partial &&
            workout.endedAt != null &&
            workout.endedAt > workout.startedAt

    fun permissionsFor(workout: Workout, samples: List<WorkoutSample> = emptyList()): Set<String> = buildSet {
        add(HealthPermission.getWritePermission(ExerciseSessionRecord::class))
        if (distanceIntervals(workout, samples).isNotEmpty()) {
            add(HealthPermission.getWritePermission(DistanceRecord::class))
        }
        if (workout.calories.isPositiveFinite()) {
            add(HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class))
        }
        if (powerSamples(workout, samples).isNotEmpty()) {
            add(HealthPermission.getWritePermission(PowerRecord::class))
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
        distanceIntervals(workout, samples).forEachIndexed { index, interval ->
            records += DistanceRecord(
                startTime = Instant.ofEpochMilli(interval.start.timestamp),
                startZoneOffset = ZoneOffset.ofTotalSeconds(interval.start.offsetSeconds),
                endTime = Instant.ofEpochMilli(interval.end.timestamp),
                endZoneOffset = ZoneOffset.ofTotalSeconds(interval.end.offsetSeconds),
                distance = interval.meters.meters,
                metadata = metadata(
                    if (index == 0) "radrower:${workout.id}:distance"
                    else "radrower:${workout.id}:distance:$index",
                    clientRecordVersion = if (index == 0) 2 else 1,
                ),
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
        val powerSamples = powerSamples(workout, samples)
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
        client.insertRecords(records(workout, samples))
    }

    private fun powerSamples(workout: Workout, samples: List<WorkoutSample>): List<PowerRecord.Sample> {
        val startAt = workout.startedAt
        val endAt = workout.endedAt ?: return emptyList()
        var sessionState: Int? = null
        val activeSamples = sortedMapOf<Long, Double>()
        for (sample in samples.sortedBy { it.sequence }) {
            val metrics = JSONObject(sample.metricsJson)
            if (metrics.has("sessionState") && !metrics.isNull("sessionState")) {
                sessionState = metrics.optInt("sessionState", -1)
            }
            if (sessionState != 2 || sample.timestamp !in startAt..endAt) continue
            val watts = metrics.opt("watts") as? Number ?: continue
            val value = watts.toDouble()
            if (value.isFinite() && value >= 0) {
                activeSamples[sample.timestamp] = value
            }
        }
        return activeSamples.map { (timestamp, value) ->
            PowerRecord.Sample(Instant.ofEpochMilli(timestamp), value.watts)
        }
    }

    private data class DistanceInterval(
        val start: WorkoutSample,
        val end: WorkoutSample,
        val meters: Double,
    )

    private fun distanceIntervals(workout: Workout, samples: List<WorkoutSample>): List<DistanceInterval> {
        val endAt = workout.endedAt ?: return emptyList()
        var sessionState: Int? = null
        var previous: Pair<WorkoutSample, Double>? = null
        val intervals = mutableListOf<DistanceInterval>()
        for (sample in samples.sortedBy { it.sequence }) {
            val metrics = JSONObject(sample.metricsJson)
            if (metrics.has("sessionState") && !metrics.isNull("sessionState")) {
                sessionState = metrics.optInt("sessionState", -1)
                if (sessionState != 2) previous = null
            }
            if (sessionState != 2 || sample.timestamp !in workout.startedAt..endAt) continue
            val distance = (metrics.opt("distance") as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it >= 0 } ?: continue
            val earlier = previous
            if (earlier != null && sample.timestamp > earlier.first.timestamp && distance > earlier.second) {
                intervals += DistanceInterval(earlier.first, sample, distance - earlier.second)
            }
            if (earlier == null || distance >= earlier.second) {
                previous = sample to distance
            }
        }
        return intervals
    }

    private fun metadata(clientRecordId: String, clientRecordVersion: Long = 1) = Metadata.activelyRecorded(
        device = Device(type = Device.TYPE_PHONE),
        clientRecordId = clientRecordId,
        clientRecordVersion = clientRecordVersion,
    )

    private fun Double?.isPositiveFinite(): Boolean = this != null && isFinite() && this > 0
}
