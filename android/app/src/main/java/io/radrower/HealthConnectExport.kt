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
    // Distance deltas are grouped into windows to keep the record count small.
    private const val DISTANCE_WINDOW_MILLIS = 60_000L

    // Health Connect guidance: at most 1000 records per write request.
    private const val MAX_RECORDS_PER_INSERT = 1_000

    // Bumped when the distance record layout changes so that re-exports overwrite older records.
    private const val DISTANCE_RECORD_VERSION = 3L

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
                    clientRecordVersion = DISTANCE_RECORD_VERSION,
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
        // Retries are idempotent thanks to stable client record IDs, so chunking is safe.
        records(workout, samples).chunked(MAX_RECORDS_PER_INSERT).forEach { client.insertRecords(it) }
    }

    private fun powerSamples(workout: Workout, samples: List<WorkoutSample>): List<PowerRecord.Sample> {
        val startAt = workout.startedAt
        val endAt = workout.endedAt ?: return emptyList()
        var sessionState: Int? = null
        val activeSamples = sortedMapOf<Long, Double>()
        for ((_, _, timestamp, _, metricsJson) in samples.sortedBy { it.sequence }) {
            val metrics = JSONObject(metricsJson)
            if (metrics.has("sessionState") && !metrics.isNull("sessionState")) {
                sessionState = metrics.optInt("sessionState", -1)
            }
            if (sessionState != 2 || timestamp !in startAt..endAt) continue
            val watts = metrics.opt("watts") as? Number ?: continue
            val value = watts.toDouble()
            if (value.isFinite() && value >= 0) {
                activeSamples[timestamp] = value
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
        val activeStretch: Int,
    )

    private fun distanceIntervals(workout: Workout, samples: List<WorkoutSample>): List<DistanceInterval> {
        val endAt = workout.endedAt ?: return emptyList()
        var sessionState: Int? = null
        var previous: Pair<WorkoutSample, Double>? = null
        var activeStretch = 0
        val intervals = mutableListOf<DistanceInterval>()
        for (sample in samples.sortedBy { it.sequence }) {
            val metrics = JSONObject(sample.metricsJson)
            if (metrics.has("sessionState") && !metrics.isNull("sessionState")) {
                sessionState = metrics.optInt("sessionState", -1)
                if (sessionState != 2 && previous != null) {
                    previous = null
                    activeStretch++
                }
            }
            if (sessionState != 2 || sample.timestamp !in workout.startedAt..endAt) continue
            val distance = (metrics.opt("distance") as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it >= 0 } ?: continue
            val earlier = previous
            if (earlier != null && sample.timestamp > earlier.first.timestamp && distance > earlier.second) {
                intervals += DistanceInterval(earlier.first, sample, distance - earlier.second, activeStretch)
            }
            if (earlier == null || distance >= earlier.second) {
                previous = sample to distance
            }
        }
        return groupByWindow(intervals)
    }

    // Merge consecutive deltas of the same active stretch into windows of at most DISTANCE_WINDOW_MILLIS.
    private fun groupByWindow(intervals: List<DistanceInterval>): List<DistanceInterval> {
        val grouped = mutableListOf<DistanceInterval>()
        for (interval in intervals) {
            val last = grouped.lastOrNull()
            if (last != null && last.activeStretch == interval.activeStretch &&
                interval.end.timestamp - last.start.timestamp <= DISTANCE_WINDOW_MILLIS
            ) {
                grouped[grouped.lastIndex] = last.copy(end = interval.end, meters = last.meters + interval.meters)
            } else {
                grouped += interval
            }
        }
        return grouped
    }

    private fun metadata(clientRecordId: String, clientRecordVersion: Long = 1) = Metadata.activelyRecorded(
        device = Device(type = Device.TYPE_PHONE),
        clientRecordId = clientRecordId,
        clientRecordVersion = clientRecordVersion,
    )

    private fun Double?.isPositiveFinite(): Boolean = this != null && isFinite() && this > 0
}
