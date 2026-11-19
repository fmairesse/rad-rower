package io.radrower

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectExportTest {
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

        val records = HealthConnectExport.records(workout, samples)

        assertEquals(5, records.size)
        val session = records.filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, session.exerciseType)
        assertEquals("Indoor rowing", session.title)
        assertEquals(Instant.ofEpochMilli(start), session.startTime)
        assertEquals(Instant.ofEpochMilli(end), session.endTime)
        assertEquals(ZoneOffset.ofHours(1), session.startZoneOffset)
        assertEquals(ZoneOffset.ofHours(1), session.endZoneOffset)
        assertEquals("radrower:workout-1:exercise", session.metadata.clientRecordId)

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
}
