package io.radrower

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class HealthConnectExportTest {
    private fun workout(
        status: String = "completed",
        partial: Boolean = false,
        endedAt: Long? = 1_700_003_600_000,
        distance: Double? = 1_234.0,
        calories: Double? = 42.0,
    ) = Workout(
        id = "workout-id",
        startedAt = 1_700_000_000_000,
        startedOffsetSeconds = 3_600,
        endedAt = endedAt,
        endedOffsetSeconds = 3_600,
        lastSampleAt = endedAt ?: 1_700_000_000_000,
        lastSampleOffsetSeconds = 3_600,
        status = status,
        partial = partial,
        distance = distance,
        calories = calories,
    )

    private fun sample(
        sequence: Int,
        timestamp: Long,
        sessionState: Int? = null,
        watts: Number? = null,
    ): WorkoutSample {
        val entries = buildList {
            sessionState?.let { add("\"sessionState\":$it") }
            watts?.let {
                val value = it.toDouble()
                add("\"watts\":${if (value.isFinite()) value else "\"NaN\""}")
            }
        }
        return WorkoutSample(
            workoutId = "workout-id",
            sequence = sequence,
            timestamp = timestamp,
            offsetSeconds = 3_600,
            metricsJson = entries.joinToString(",", "{", "}"),
        )
    }

    @Test
    fun exportsRowingSessionAndAvailableMeasuredTotals() {
        val workout = workout()
        val records = HealthConnectExport.records(workout)

        assertEquals(3, records.size)
        val session = records[0] as ExerciseSessionRecord
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, session.exerciseType)
        assertEquals(Instant.ofEpochMilli(workout.startedAt), session.startTime)
        assertEquals(Instant.ofEpochMilli(requireNotNull(workout.endedAt)), session.endTime)
        assertEquals("Indoor rowing", session.title)
        assertEquals("radrower:workout-id:exercise", session.metadata.clientRecordId)
        assertEquals("radrower:workout-id:distance", (records[1] as DistanceRecord).metadata.clientRecordId)
        assertEquals(
            "radrower:workout-id:active-calories",
            (records[2] as ActiveCaloriesBurnedRecord).metadata.clientRecordId,
        )
        assertEquals(
            setOf(
                "android.permission.health.WRITE_EXERCISE",
                "android.permission.health.WRITE_DISTANCE",
                "android.permission.health.WRITE_ACTIVE_CALORIES_BURNED",
            ),
            HealthConnectExport.permissionsFor(workout),
        )
    }

    @Test
    fun omitsUnavailableOrNonpositiveTotalsAndTheirPermissions() {
        val workout = workout(distance = null, calories = 0.0)

        assertEquals(1, HealthConnectExport.records(workout).size)
        assertEquals(
            setOf("android.permission.health.WRITE_EXERCISE"),
            HealthConnectExport.permissionsFor(workout),
        )
    }

    @Test
    fun exportsMeasuredPowerSamplesOnlyWhileSessionIsActive() {
        val workout = workout()
        val samples = listOf(
            sample(0, workout.startedAt, sessionState = 2, watts = 0),
            sample(1, workout.startedAt + 1_000, watts = 120),
            sample(2, workout.startedAt + 2_000, sessionState = 3, watts = 0),
            sample(3, workout.startedAt + 3_000, watts = 250),
            sample(4, workout.startedAt + 4_000, sessionState = 2, watts = 140),
            sample(5, workout.endedAt!!, sessionState = 4, watts = 0),
            sample(6, workout.endedAt!! + 1_000, sessionState = 2, watts = 180),
            sample(7, workout.startedAt + 5_000, sessionState = 2, watts = Double.NaN),
            sample(8, workout.startedAt + 6_000, sessionState = 2, watts = -1),
        )

        val records = HealthConnectExport.records(workout, samples)
        val power = records.filterIsInstance<PowerRecord>().single()

        assertEquals(
            listOf(workout.startedAt, workout.startedAt + 1_000, workout.startedAt + 4_000),
            power.samples.map { it.time.toEpochMilli() },
        )
        assertEquals(listOf(0.0, 120.0, 140.0), power.samples.map { it.power.inWatts })
        assertEquals("radrower:workout-id:power", power.metadata.clientRecordId)
        assertTrue("android.permission.health.WRITE_POWER" in HealthConnectExport.permissionsFor(workout, samples))
    }

    @Test
    fun omitsPowerRecordAndPermissionWithoutActiveMeasurements() {
        val workout = workout()
        val samples = listOf(
            sample(0, workout.startedAt, sessionState = 3, watts = 180),
            sample(1, workout.startedAt + 1_000, watts = 150),
        )

        assertTrue(HealthConnectExport.records(workout, samples).none { it is PowerRecord })
        assertFalse("android.permission.health.WRITE_POWER" in HealthConnectExport.permissionsFor(workout, samples))
    }

    @Test
    fun onlyCompleteNonPartialWorkoutsWithValidTimesCanBeExported() {
        assertTrue(HealthConnectExport.isEligible(workout()))
        assertFalse(HealthConnectExport.isEligible(workout(status = "interrupted")))
        assertFalse(HealthConnectExport.isEligible(workout(partial = true)))
        assertFalse(HealthConnectExport.isEligible(workout(endedAt = null)))
        assertFalse(HealthConnectExport.isEligible(workout(endedAt = 1_700_000_000_000)))
    }
}
