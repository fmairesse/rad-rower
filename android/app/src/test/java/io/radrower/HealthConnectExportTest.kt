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

    private fun workout(distanceM: Double? = 1_234.0) = Workout(
        id = "workout",
        startedAtEpochMs = start,
        startedOffsetS = 0,
        endedAtEpochMs = start + 10_000,
        lastSampleAtEpochMs = start + 10_000,
        lastSampleOffsetS = 0,
        status = "completed",
        partial = false,
        distanceM = distanceM,
        caloriesKcal = 12.5,
    )

    private fun sample(
        sequence: Int,
        elapsedMs: Long,
        distanceM: Double? = null,
        sessionState: Int = SessionState.IN_PROGRESS.code,
        powerW: Double? = null,
        speedKmh: Double? = null,
        id: String = "workout",
    ) = WorkoutSample(
        workoutId = id,
        sequence = sequence,
        timestampEpochMs = start + elapsedMs,
        offsetS = 0,
        sessionState = sessionState,
        powerW = powerW,
        resistanceLevel = 1,
        distanceM = distanceM,
        speedKmh = speedKmh,
    )

    @Test
    fun exportsSessionActiveCaloriesAndPowerButNoSpeedOrLaps() {
        val workout = workout(distanceM = null)
        val samples = listOf(
            sample(0, 0, powerW = 100.0, speedKmh = 18.0),
            sample(1, 1_000, powerW = 150.0, speedKmh = 21.6),
        )

        val records = HealthConnectExport.records(workout, samples)

        val session = records.filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, session.exerciseType)
        assertEquals("Indoor rowing", session.title)
        assertEquals(Instant.ofEpochMilli(start), session.startTime)
        assertEquals(Instant.ofEpochMilli(start + 10_000), session.endTime)
        assertEquals(ZoneOffset.UTC, session.startZoneOffset)
        assertEquals("radrower:workout:exercise", session.metadata.clientRecordId)
        assertEquals(4L, session.metadata.clientRecordVersion)
        assertTrue(session.laps.isEmpty())

        val activeCalories = records.filterIsInstance<ActiveCaloriesBurnedRecord>().single()
        assertEquals(12.5, activeCalories.energy.inKilocalories, 0.0)
        assertEquals("radrower:workout:active-calories", activeCalories.metadata.clientRecordId)

        val power = records.filterIsInstance<PowerRecord>().single()
        assertEquals(listOf(start, start + 1_000), power.samples.map { it.time.toEpochMilli() })
        assertTrue(records.none { it is SpeedRecord })
        assertEquals(
            setOf(
                "android.permission.health.WRITE_EXERCISE",
                "android.permission.health.WRITE_ACTIVE_CALORIES_BURNED",
                "android.permission.health.WRITE_POWER",
            ),
            HealthConnectExport.permissionsFor(workout, samples),
        )
        assertEquals(
            setOf(
                "android.permission.health.WRITE_EXERCISE",
                "android.permission.health.WRITE_DISTANCE",
                "android.permission.health.WRITE_ACTIVE_CALORIES_BURNED",
                "android.permission.health.WRITE_POWER",
            ),
            HealthConnectExport.allWritePermissions,
        )
    }

    @Test
    fun interpolatesDistanceIntoOneSecondRecordsAndAClippedFinalRecord() {
        val samples = listOf(
            sample(0, 0, distanceM = 0.0),
            sample(1, 3_500, distanceM = 35.0),
            sample(2, 4_500, distanceM = 55.0),
        )

        val distances = HealthConnectExport.records(workout(), samples).filterIsInstance<DistanceRecord>()

        assertEquals(
            listOf(
                start to start + 1_000,
                start + 1_000 to start + 2_000,
                start + 2_000 to start + 3_000,
                start + 3_000 to start + 4_000,
                start + 4_000 to start + 4_500,
            ),
            distances.map { it.startTime.toEpochMilli() to it.endTime.toEpochMilli() },
        )
        assertEquals(listOf(10.0, 10.0, 10.0, 15.0, 10.0), distances.map { it.distance.inMeters })
        assertEquals(55.0, distances.sumOf { it.distance.inMeters }, 1e-9)
        assertEquals("radrower:workout:distance", distances.first().metadata.clientRecordId)
        assertEquals("radrower:workout:distance:4", distances.last().metadata.clientRecordId)
        assertTrue(distances.all { it.metadata.clientRecordVersion == 7L })
    }

    @Test
    fun excludesPausesAndCounterResetsFromDistanceWindows() {
        val samples = listOf(
            sample(0, 0, distanceM = 0.0),
            sample(1, 2_000, distanceM = 20.0),
            sample(2, 3_000, distanceM = 0.0, sessionState = SessionState.PAUSED.code),
            sample(3, 4_000, distanceM = 0.0),
            sample(4, 5_000, distanceM = 0.0, sessionState = SessionState.IN_PROGRESS.code),
            sample(5, 7_000, distanceM = 30.0),
        )

        val distances = HealthConnectExport.records(workout(), samples).filterIsInstance<DistanceRecord>()

        assertEquals(listOf(10.0, 10.0, 15.0, 15.0), distances.map { it.distance.inMeters })
        assertEquals(
            listOf(
                start to start + 1_000,
                start + 1_000 to start + 2_000,
                start + 5_000 to start + 6_000,
                start + 6_000 to start + 7_000,
            ),
            distances.map { it.startTime.toEpochMilli() to it.endTime.toEpochMilli() },
        )
    }

    @Test
    fun requiresActiveDistanceSamplesRatherThanSummaryOnly() {
        val workout = workout(distanceM = 1_234.0)

        assertTrue(HealthConnectExport.records(workout).none { it is DistanceRecord })
        assertTrue(
            "android.permission.health.WRITE_DISTANCE" !in HealthConnectExport.permissionsFor(workout),
        )
    }

    @Test
    fun omitsZeroDurationSegmentsButRejectsInvalidSegments() {
        val workout = Workout(
            id = "test",
            startedAtEpochMs = 100,
            startedOffsetS = 0,
            endedAtEpochMs = 300,
            lastSampleAtEpochMs = 300,
            lastSampleOffsetS = 0,
            status = "completed",
            partial = false,
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
}
