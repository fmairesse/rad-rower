package io.radrower

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseSegment
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
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToLong

internal object HealthConnectExport {
    data class ExportResult(val lapsUnavailable: Boolean)

    // Health Connect guidance: at most 1000 records per write request.
    private const val MAX_RECORDS_PER_INSERT = 1_000

    // Bumped when the distance record layout changes so that re-exports overwrite older records.
    private const val DISTANCE_RECORD_VERSION = 6L
    private const val EXERCISE_RECORD_VERSION = 3L
    private const val LAP_DISTANCE_M = 500.0

    fun isEligible(workout: Workout): Boolean =
        workout.status == "completed" &&
            !workout.partial &&
            workout.endedAtEpochMs != null &&
            workout.endedAtEpochMs > workout.startedAtEpochMs

    fun permissionsFor(workout: Workout, samples: List<WorkoutSample> = emptyList()): Set<String> = buildSet {
        add(HealthPermission.getWritePermission(ExerciseSessionRecord::class))
        if (workout.distanceM.isPositiveFinite()) {
            add(HealthPermission.getWritePermission(DistanceRecord::class))
        }
        if (workout.caloriesKcal.isPositiveFinite()) {
            add(HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class))
        }
        if (activeMetricSamples(workout, samples) { it.powerW }.isNotEmpty()) {
            add(HealthPermission.getWritePermission(PowerRecord::class))
        }
        if (activeMetricSamples(workout, samples) { it.speedKmh }.isNotEmpty()) {
            add(HealthPermission.getWritePermission(SpeedRecord::class))
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
                laps = workoutLaps(workout, samples, segments),
                metadata = metadata(sessionId, clientRecordVersion = EXERCISE_RECORD_VERSION),
            ),
        )
        workout.distanceM.takeIf { it.isPositiveFinite() }?.let { distanceM ->
            records += DistanceRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                distance = distanceM.meters,
                metadata = metadata("radrower:${workout.id}:distance", clientRecordVersion = DISTANCE_RECORD_VERSION),
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
        val speedSamples = activeMetricSamples(workout, samples) { it.speedKmh }.map { (timestampEpochMs, speedKmh) ->
            SpeedRecord.Sample(Instant.ofEpochMilli(timestampEpochMs), (speedKmh / 3.6).metersPerSecond)
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

    private fun workoutLaps(
        workout: Workout,
        samples: List<WorkoutSample>,
        segments: List<Segment>,
    ): List<ExerciseLap> {
        val total = workout.distanceM?.takeIf { it.isPositiveFinite() } ?: return emptyList()
        val endMs = requireNotNull(workout.endedAtEpochMs)
        val pauses = segments.filter { it.type == "pause" }.sortedBy { it.startedAtEpochMs }
        val readings = samples.asSequence()
            .filter { it.workoutId == workout.id && it.timestampEpochMs in workout.startedAtEpochMs..endMs }
            .filter { it.distanceM != null && it.distanceM.isFinite() && it.distanceM >= 0 && it.distanceM <= total }
            .sortedWith(compareBy<WorkoutSample> { it.timestampEpochMs }.thenBy { it.sequence })

        var previousMs = workout.startedAtEpochMs
        var previousDistance = 0.0
        var nextBoundary = LAP_DISTANCE_M
        var lapStart = Instant.ofEpochMilli(workout.startedAtEpochMs)
        val laps = mutableListOf<ExerciseLap>()
        for (sample in readings) {
            val distance = requireNotNull(sample.distanceM)
            if (distance < previousDistance) continue
            if (distance == previousDistance) {
                previousMs = sample.timestampEpochMs
                continue
            }
            while (nextBoundary <= distance) {
                val fraction = (nextBoundary - previousDistance) / (distance - previousDistance)
                val crossing = interpolateActiveTime(previousMs, sample.timestampEpochMs, fraction, pauses)
                    ?: return emptyList()
                if (!crossing.isAfter(lapStart)) return emptyList()
                laps += ExerciseLap(lapStart, crossing, LAP_DISTANCE_M.meters)
                lapStart = crossing
                nextBoundary += LAP_DISTANCE_M
            }
            previousMs = sample.timestampEpochMs
            previousDistance = distance
        }
        if (nextBoundary <= total) return emptyList()
        val remaining = total - (nextBoundary - LAP_DISTANCE_M)
        if (remaining > 0) {
            val end = Instant.ofEpochMilli(endMs)
            if (!end.isAfter(lapStart)) return emptyList()
            laps += ExerciseLap(lapStart, end, remaining.meters)
        }
        return laps
    }

    private fun interpolateActiveTime(
        startMs: Long,
        endMs: Long,
        fraction: Double,
        pauses: List<Segment>,
    ): Instant? {
        val intervals = mutableListOf<Pair<Long, Long>>()
        var cursor = startMs
        for (pause in pauses) {
            val pauseStart = pause.startedAtEpochMs.coerceIn(startMs, endMs)
            val pauseEnd = requireNotNull(pause.endedAtEpochMs).coerceIn(startMs, endMs)
            if (pauseStart > cursor) intervals += cursor to pauseStart
            cursor = maxOf(cursor, pauseEnd)
        }
        if (cursor < endMs) intervals += cursor to endMs
        val activeMs = intervals.sumOf { (start, end) -> end - start }
        if (activeMs <= 0) return null
        var remainingMs = activeMs.toDouble() * fraction
        for ((start, end) in intervals) {
            val durationMs = end - start
            if (remainingMs <= durationMs) {
                return Instant.ofEpochMilli(start).plusNanos((remainingMs * 1_000_000).roundToLong())
            }
            remainingMs -= durationMs
        }
        return Instant.ofEpochMilli(endMs)
    }

    suspend fun export(
        client: HealthConnectClient,
        workout: Workout,
        samples: List<WorkoutSample> = emptyList(),
        segments: List<Segment> = emptyList(),
    ): ExportResult {
        val requiredPermissions = permissionsFor(workout, samples)
        val grantedPermissions = client.permissionController.getGrantedPermissions()
        check(grantedPermissions.containsAll(requiredPermissions)) {
            "Health Connect permissions are missing. Grant access and try again."
        }
        val records = records(workout, samples, segments)
        // Retries are idempotent thanks to stable client record IDs, so chunking is safe.
        records.chunked(MAX_RECORDS_PER_INSERT).forEach { client.insertRecords(it) }
        return ExportResult(
            lapsUnavailable = workout.distanceM.isPositiveFinite() &&
                records.filterIsInstance<ExerciseSessionRecord>().single().laps.isEmpty(),
        )
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
