package io.radrower

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.kilocalories
import androidx.health.connect.client.units.meters
import androidx.health.connect.client.units.watts
import java.time.Instant
import java.time.ZoneOffset

internal object HealthConnectExport {
    private const val DISTANCE_WINDOW_MILLIS = 1_000L

    val allWritePermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(PowerRecord::class),
    )

    // Health Connect guidance: at most 1000 records per write request.
    private const val MAX_RECORDS_PER_INSERT = 1_000

    // Bumped when the distance record layout changes so that re-exports overwrite older records.
    private const val DISTANCE_RECORD_VERSION = 7L
    private const val EXERCISE_RECORD_VERSION = 4L

    fun isEligible(workout: Workout): Boolean =
        workout.status == "completed" &&
            !workout.partial &&
            workout.endedAtEpochMs != null &&
            workout.endedAtEpochMs > workout.startedAtEpochMs

    fun permissionsFor(workout: Workout, samples: List<WorkoutSample> = emptyList()): Set<String> = buildSet {
        add(HealthPermission.getWritePermission(ExerciseSessionRecord::class))
        if (distanceIntervals(workout, samples).isNotEmpty()) {
            add(HealthPermission.getWritePermission(DistanceRecord::class))
        }
        if (workout.caloriesKcal.isPositiveFinite()) {
            add(HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class))
        }
        if (activeMetricSamples(workout, samples) { it.powerW }.isNotEmpty()) {
            add(HealthPermission.getWritePermission(PowerRecord::class))
        }
    }

    fun records(
        workout: Workout,
        samples: List<WorkoutSample> = emptyList(),
        segments: List<Segment> = emptyList(),
    ): List<Record> {
        require(isEligible(workout)) {
            "Only completed, non-partial workouts with a valid time range can be exported."
        }
        val start = Instant.ofEpochMilli(workout.startedAtEpochMs)
        val end = Instant.ofEpochMilli(requireNotNull(workout.endedAtEpochMs))
        val startOffset = ZoneOffset.ofTotalSeconds(workout.startedOffsetS)
        val endOffset = ZoneOffset.ofTotalSeconds(
            workout.endedOffsetS ?: workout.lastSampleOffsetS,
        )
        val sessionId = "radrower:${workout.id}:exercise"
        val exerciseSegments = segments.mapNotNull { segment ->
            require(segment.workoutId == workout.id) { "Segment belongs to another workout." }
            val segmentEnd = requireNotNull(segment.endedAtEpochMs) { "Workout segment is not closed." }
            require(segment.startedAtEpochMs in workout.startedAtEpochMs..segmentEnd && segmentEnd <= workout.endedAtEpochMs) {
                "Workout segment has an invalid time range."
            }
            val type = when (segment.type) {
                "rowing" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE
                "pause" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE
                else -> error("Unsupported workout segment type: ${segment.type}")
            }
            if (segment.startedAtEpochMs == segmentEnd) null else ExerciseSegment(
                startTime = Instant.ofEpochMilli(segment.startedAtEpochMs),
                endTime = Instant.ofEpochMilli(segmentEnd),
                segmentType = type,
            )
        }
        val records = mutableListOf<Record>(
            ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
                title = "Indoor rowing",
                segments = exerciseSegments,
                metadata = metadata(sessionId, clientRecordVersion = EXERCISE_RECORD_VERSION),
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
        workout.caloriesKcal.takeIf { it.isPositiveFinite() }?.let { caloriesKcal ->
            records += ActiveCaloriesBurnedRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                energy = caloriesKcal.kilocalories,
                metadata = metadata("radrower:${workout.id}:active-calories"),
            )
        }
        val powerSamples = activeMetricSamples(workout, samples) { it.powerW }.map { (timestampEpochMs, powerW) ->
            PowerRecord.Sample(Instant.ofEpochMilli(timestampEpochMs), powerW.watts)
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
        return records
    }

    suspend fun export(
        client: HealthConnectClient,
        workout: Workout,
        samples: List<WorkoutSample> = emptyList(),
        segments: List<Segment> = emptyList(),
    ) {
        val requiredPermissions = permissionsFor(workout, samples)
        val grantedPermissions = client.permissionController.getGrantedPermissions()
        check(grantedPermissions.containsAll(requiredPermissions)) {
            "Health Connect permissions are missing. Grant access and try again."
        }
        val records = records(workout, samples, segments)
        // Retries are idempotent thanks to stable client record IDs, so chunking is safe.
        records.chunked(MAX_RECORDS_PER_INSERT).forEach { client.insertRecords(it) }
    }

    private data class DistancePoint(val timestamp: Long, val offsetSeconds: Int, val meters: Double)

    private data class DistanceInterval(val start: DistancePoint, val end: DistancePoint, val meters: Double)

    private fun distanceIntervals(workout: Workout, samples: List<WorkoutSample>): List<DistanceInterval> {
        val endAt = workout.endedAtEpochMs ?: return emptyList()
        var sessionState: Int?
        val stretches = mutableListOf<MutableList<DistancePoint>>()
        var current: MutableList<DistancePoint>? = null
        for ((workoutId, _, timestampEpochMs, offsetS, sessionState1, _, distanceM) in samples.sortedBy { it.sequence }) {
            sessionState = sessionState1
            if (sessionState != SessionState.IN_PROGRESS.code) current = null
            if (sessionState != SessionState.IN_PROGRESS.code ||
                workoutId != workout.id ||
                timestampEpochMs !in workout.startedAtEpochMs..endAt
            ) continue
            val distance = distanceM?.takeIf { it.isFinite() && it >= 0 } ?: continue
            val points = current ?: mutableListOf<DistancePoint>().also {
                stretches += it
                current = it
            }
            val last = points.lastOrNull()
            if (last == null || (distance >= last.meters && timestampEpochMs > last.timestamp)) {
                points += DistancePoint(timestampEpochMs, offsetS, distance)
            }
        }
        return stretches.flatMap(::windows)
    }

    // Interpolate irregular rower updates at fixed boundaries to make each record represent one second.
    private fun windows(points: List<DistancePoint>): List<DistanceInterval> {
        val first = points.firstOrNull() ?: return emptyList()
        val lastTimestamp = points.last().timestamp
        val intervals = mutableListOf<DistanceInterval>()
        var start = first.timestamp
        while (start < lastTimestamp) {
            val end = minOf(start + DISTANCE_WINDOW_MILLIS, lastTimestamp)
            val startPoint = DistancePoint(start, points.offsetAt(start), points.metersAt(start))
            val endPoint = DistancePoint(end, points.offsetAt(end), points.metersAt(end))
            val meters = endPoint.meters - startPoint.meters
            if (meters > 0) intervals += DistanceInterval(startPoint, endPoint, meters)
            start = end
        }
        return intervals
    }

    private fun List<DistancePoint>.offsetAt(timestamp: Long): Int =
        lastOrNull { it.timestamp <= timestamp }?.offsetSeconds ?: first().offsetSeconds

    private fun List<DistancePoint>.metersAt(timestamp: Long): Double {
        val after = indexOfFirst { it.timestamp >= timestamp }
        if (after <= 0) return first().meters
        val before = this[after - 1]
        val next = this[after]
        return before.meters + (next.meters - before.meters) *
            (timestamp - before.timestamp) / (next.timestamp - before.timestamp)
    }

    private fun activeMetricSamples(
        workout: Workout,
        samples: List<WorkoutSample>,
        metric: (WorkoutSample) -> Double?,
    ): Map<Long, Double> {
        val startAtEpochMs = workout.startedAtEpochMs
        val endAtEpochMs = workout.endedAtEpochMs ?: return emptyMap()
        val activeSamples = sortedMapOf<Long, Double>()
        for (sample in samples.sortedBy { it.sequence }) {
            if (sample.sessionState != SessionState.IN_PROGRESS.code ||
                sample.timestampEpochMs !in startAtEpochMs..endAtEpochMs
            ) continue
            val value = metric(sample) ?: continue
            if (value.isFinite() && value >= 0) {
                activeSamples[sample.timestampEpochMs] = value
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
