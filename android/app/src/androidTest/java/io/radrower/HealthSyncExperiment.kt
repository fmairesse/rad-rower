package io.radrower

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.meters
import androidx.health.connect.client.units.metersPerSecond
import androidx.health.connect.client.units.watts
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToLong

internal object HealthSyncExperiment {
    val start: Instant = Instant.parse("2026-11-19T07:00:00Z")
    val pauseStart: Instant = start.plusSeconds(300)
    val pauseEnd: Instant = pauseStart.plusSeconds(60)
    val end: Instant = pauseEnd.plusSeconds(300)
    private val offset = ZoneOffset.ofHours(1)
    private const val SAMPLE_COUNT = 600

    private fun metadata(type: String) = Metadata.activelyRecorded(
        device = Device(type = Device.TYPE_PHONE),
        clientRecordId = "radrower-experiment:I1:$type",
        clientRecordVersion = 4,
    )

    fun records(): List<Record> {
        fun sampleTime(activeSecond: Int): Instant =
            if (activeSecond < 300) start.plusSeconds(activeSecond.toLong())
            else pauseEnd.plusSeconds((activeSecond - 300).toLong())
        val speedSamples = (0 until SAMPLE_COUNT).map { second ->
            SpeedRecord.Sample(sampleTime(second), (2.0 + 0.005 * second).metersPerSecond)
        }
        val powerSamples = (0 until SAMPLE_COUNT).map { second ->
            PowerRecord.Sample(sampleTime(second), (100.0 + second).watts)
        }
        // Each speed sample represents one second, including the interval after the last sample.
        val totalDistance = speedSamples.sumOf { it.speed.inMetersPerSecond }
        val laps = mutableListOf<ExerciseLap>()
        var lapStart = start
        var distanceSoFar = 0.0
        var nextLapDistance = 500.0
        for (sample in speedSamples) {
            val speed = sample.speed.inMetersPerSecond
            val nextDistance = distanceSoFar + speed
            if (nextDistance >= nextLapDistance) {
                val fraction = (nextLapDistance - distanceSoFar) / speed
                val lapEnd = sample.time.plusNanos((fraction * 1_000_000_000).roundToLong())
                laps += ExerciseLap(lapStart, lapEnd, 500.0.meters)
                lapStart = lapEnd
                nextLapDistance += 500.0
            }
            distanceSoFar = nextDistance
        }
        laps += ExerciseLap(lapStart, end, (totalDistance - (nextLapDistance - 500.0)).meters)
        return listOf<Record>(
            ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = offset,
                endTime = end,
                endZoneOffset = offset,
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
                title = "Radrower experiment I1",
                segments = listOf(
                    ExerciseSegment(start, pauseStart, ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE),
                    ExerciseSegment(pauseStart, pauseEnd, ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE),
                    ExerciseSegment(pauseEnd, end, ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE),
                ),
                laps = laps,
                metadata = metadata("exercise"),
            ),
            PowerRecord(
                startTime = start,
                startZoneOffset = offset,
                endTime = end,
                endZoneOffset = offset,
                samples = powerSamples,
                metadata = metadata("power"),
            ),
            SpeedRecord(
                startTime = start,
                startZoneOffset = offset,
                endTime = end,
                endZoneOffset = offset,
                samples = speedSamples,
                metadata = metadata("speed"),
            ),
            DistanceRecord(
                startTime = start,
                startZoneOffset = offset,
                endTime = end,
                endZoneOffset = offset,
                distance = totalDistance.meters,
                metadata = metadata("distance"),
            ),
        )
    }

    suspend fun insert(client: HealthConnectClient) {
        val required = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(PowerRecord::class),
            HealthPermission.getWritePermission(SpeedRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
        )
        val missing = required - client.permissionController.getGrantedPermissions()
        check(missing.isEmpty()) { "Missing permissions: $missing. Grant them in Health Connect." }
        client.insertRecords(records())
    }
}
