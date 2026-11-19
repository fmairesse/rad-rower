package io.radrower

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectExportTest {
    private val start = 1_700_000_000_000L

    private fun workout(distanceM: Double?) = Workout(
        id = "laps",
        startedAtEpochMs = start,
        startedOffsetS = 0,
        endedAtEpochMs = start + 10_000,
        lastSampleAtEpochMs = start + 10_000,
        lastSampleOffsetS = 0,
        status = "completed",
        partial = false,
        distanceM = distanceM,
    )

    private fun distanceSample(sequence: Int, elapsedMs: Long, distanceM: Double?, id: String = "laps") =
        WorkoutSample(
            workoutId = id,
            sequence = sequence,
            timestampEpochMs = start + elapsedMs,
            offsetS = 0,
            sessionState = 2,
            resistanceLevel = 1,
            distanceM = distanceM,
        )

    private fun laps(
        distanceM: Double?,
        samples: List<WorkoutSample>,
        segments: List<Segment> = emptyList(),
    ) = HealthConnectExport.records(workout(distanceM), samples, segments)
        .filterIsInstance<ExerciseSessionRecord>().single().laps

    @Test
    fun createsHealthConnectRecordsFromWorkoutAndSamples() {
        val start = 1_700_000_000_000L
        val end = start + 10_000
        val workout = Workout(
            id = "workout-1",
            startedAtEpochMs = start,
            startedOffsetS = 3_600,
            endedAtEpochMs = end,
            endedOffsetS = 3_600,
            lastSampleAtEpochMs = end,
            lastSampleOffsetS = 3_600,
            status = "completed",
            partial = false,
            distanceM = 123.0,
            caloriesKcal = 12.5,
        )
        fun sample(
            sequence: Int,
            elapsedMs: Long,
            sessionState: Int,
            powerW: Double,
            speedKmh: Double,
        ) = WorkoutSample(
            workoutId = workout.id,
            sequence = sequence,
            timestampEpochMs = start + elapsedMs,
            offsetS = 3_600,
            sessionState = sessionState,
            powerW = powerW,
            resistanceLevel = 1,
            speedKmh = speedKmh,
        )
        val samples = listOf(
            sample(0, 0, 2, 100.0, 18.0),
            sample(1, 1_000, 2, 150.0, 21.6),
            sample(2, 2_000, 3, 0.0, 0.0),
            sample(3, 3_000, 3, 200.0, 36.0),
            sample(4, 4_000, 2, 180.0, 25.2),
        )

        val segments = listOf(
            Segment(workout.id, 0, "rowing", start, start + 2_000),
            Segment(workout.id, 1, "pause", start + 2_000, start + 4_000),
            Segment(workout.id, 2, "rowing", start + 4_000, end),
        )
        val records = HealthConnectExport.records(workout, samples, segments)

        assertEquals(5, records.size)
        val session = records.filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, session.exerciseType)
        assertEquals("Indoor rowing", session.title)
        assertEquals(Instant.ofEpochMilli(start), session.startTime)
        assertEquals(Instant.ofEpochMilli(end), session.endTime)
        assertEquals(ZoneOffset.ofHours(1), session.startZoneOffset)
        assertEquals(ZoneOffset.ofHours(1), session.endZoneOffset)
        assertEquals("radrower:workout-1:exercise", session.metadata.clientRecordId)
        assertEquals(3L, session.metadata.clientRecordVersion)
        assertEquals(
            listOf(
                ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE,
                ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE,
                ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE,
            ),
            session.segments.map { it.segmentType },
        )
        assertEquals(segments.map { it.startedAtEpochMs }, session.segments.map { it.startTime.toEpochMilli() })
        assertEquals(segments.map { it.endedAtEpochMs }, session.segments.map { it.endTime.toEpochMilli() })

        val distance = records.filterIsInstance<DistanceRecord>().single()
        assertEquals(123.0, distance.distance.inMeters, 0.0)
        assertEquals(session.startTime, distance.startTime)
        assertEquals(session.endTime, distance.endTime)
        assertEquals("radrower:workout-1:distance", distance.metadata.clientRecordId)
        assertEquals(6L, distance.metadata.clientRecordVersion)

        val calories = records.filterIsInstance<ActiveCaloriesBurnedRecord>().single()
        assertEquals(12.5, calories.energy.inKilocalories, 0.0)
        assertEquals("radrower:workout-1:active-calories", calories.metadata.clientRecordId)

        val power = records.filterIsInstance<PowerRecord>().single()
        assertEquals(listOf(start, start + 1_000, start + 4_000), power.samples.map { it.time.toEpochMilli() })
        assertEquals(listOf(100.0, 150.0, 180.0), power.samples.map { it.power.inWatts })
        assertEquals("radrower:workout-1:power", power.metadata.clientRecordId)

        val speed = records.filterIsInstance<SpeedRecord>().single()
        assertEquals(listOf(start, start + 1_000, start + 4_000), speed.samples.map { it.time.toEpochMilli() })
        assertEquals(5.0, speed.samples[0].speed.inMetersPerSecond, 1e-9)
        assertEquals(6.0, speed.samples[1].speed.inMetersPerSecond, 1e-9)
        assertEquals(7.0, speed.samples[2].speed.inMetersPerSecond, 1e-9)
        assertEquals("radrower:workout-1:speed", speed.metadata.clientRecordId)

        assertEquals(
            setOf(
                "android.permission.health.WRITE_EXERCISE",
                "android.permission.health.WRITE_DISTANCE",
                "android.permission.health.WRITE_ACTIVE_CALORIES_BURNED",
                "android.permission.health.WRITE_POWER",
                "android.permission.health.WRITE_SPEED",
            ),
            HealthConnectExport.permissionsFor(workout, samples),
        )
    }

    @Test
    fun omitsZeroDurationSegmentsButRejectsInvalidSegments() {
        val workout = Workout(
            id = "test", startedAtEpochMs = 100, startedOffsetS = 0,
            endedAtEpochMs = 300, lastSampleAtEpochMs = 300, lastSampleOffsetS = 0,
            status = "completed", partial = false,
        )
        val session = HealthConnectExport.records(
            workout,
            segments = listOf(
                Segment("test", 0, "rowing", 100, 200),
                Segment("test", 1, "pause", 200, 200),
                Segment("test", 2, "rowing", 200, 300),
            ),
        ).filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals(2, session.segments.size)
        listOf(
            Segment("other", 0, "rowing", 100, 200),
            Segment("test", 0, "rowing", 100, null),
            Segment("test", 0, "rowing", 99, 200),
            Segment("test", 0, "rowing", 200, 301),
            Segment("test", 0, "unknown", 100, 200),
        ).forEach { segment ->
            try {
                HealthConnectExport.records(workout, segments = listOf(segment))
                org.junit.Assert.fail("Expected invalid segment to be rejected: $segment")
            } catch (_: IllegalArgumentException) {
            } catch (_: IllegalStateException) {
            }
        }
    }

    @Test
    fun interpolatesLapsAcrossKnownPauseAndIncludesRemainder() {
        val samples = listOf(
            distanceSample(0, 0, 0.0),
            distanceSample(1, 1_000, 400.0),
            distanceSample(2, 9_000, 1_200.0),
        )
        val segments = listOf(
            Segment("laps", 0, "rowing", start, start + 3_000),
            Segment("laps", 1, "pause", start + 3_000, start + 7_000),
            Segment("laps", 2, "rowing", start + 7_000, start + 10_000),
        )
        val laps = laps(1_200.0, samples, segments)
        assertEquals(listOf(500.0, 500.0, 200.0), laps.map { it.length!!.inMeters })
        assertEquals(
            listOf(start + 1_500, start + 8_000, start + 10_000),
            laps.map { it.endTime.toEpochMilli() },
        )
        assertEquals(
            listOf(start, start + 1_500, start + 8_000),
            laps.map { it.startTime.toEpochMilli() },
        )
    }

    @Test
    fun handlesCounterPlateausResetsAndExactDistance() {
        val samples = listOf(
            distanceSample(0, 0, 0.0),
            distanceSample(1, 1_000, 400.0),
            distanceSample(2, 4_000, 400.0),
            distanceSample(3, 5_000, 0.0),
            distanceSample(4, 6_000, 600.0),
            distanceSample(5, 10_000, 1_000.0),
        )
        val laps = laps(1_000.0, samples)
        assertEquals(listOf(500.0, 500.0), laps.map { it.length!!.inMeters })
        assertEquals(listOf(start + 5_000, start + 10_000), laps.map { it.endTime.toEpochMilli() })
    }

    @Test
    fun ignoresInvalidAndUnrelatedReadingsAndExportsShortWorkout() {
        val samples = listOf(
            distanceSample(0, 0, 0.0),
            distanceSample(1, 1_000, Double.NaN),
            distanceSample(2, 2_000, -1.0),
            distanceSample(3, 3_000, 600.0, id = "other"),
            distanceSample(4, 4_000, 1_000.0),
            distanceSample(5, 5_000, 600.0),
            distanceSample(6, 11_000, 600.0),
        )
        val laps = laps(600.0, samples)
        assertEquals(listOf(500.0, 100.0), laps.map { it.length!!.inMeters })
        assertEquals(start + 4_166, laps.first().endTime.toEpochMilli())
        assertEquals(listOf(123.0), laps(123.0, emptyList()).map { it.length!!.inMeters })
        assertTrue(laps(null, samples).isEmpty())
        assertTrue(laps(0.0, samples).isEmpty())
    }

    @Test
    fun omitsLapsWhenCrossingsCannotBeLocated() {
        assertTrue(laps(1_000.0, emptyList()).isEmpty())
        assertTrue(laps(1_000.0, listOf(distanceSample(0, 5_000, 700.0))).isEmpty())
        assertTrue(laps(500.0, listOf(distanceSample(0, 0, 500.0))).isEmpty())
        val segments = listOf(Segment("laps", 0, "pause", start, start + 10_000))
        assertTrue(laps(500.0, listOf(distanceSample(0, 10_000, 500.0)), segments).isEmpty())
    }
}
