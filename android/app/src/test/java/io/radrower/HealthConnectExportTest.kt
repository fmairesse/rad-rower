package io.radrower

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

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
    fun onlyCompleteNonPartialWorkoutsWithValidTimesCanBeExported() {
        assertTrue(HealthConnectExport.isEligible(workout()))
        assertFalse(HealthConnectExport.isEligible(workout(status = "interrupted")))
        assertFalse(HealthConnectExport.isEligible(workout(partial = true)))
        assertFalse(HealthConnectExport.isEligible(workout(endedAt = null)))
        assertFalse(HealthConnectExport.isEligible(workout(endedAt = 1_700_000_000_000)))
    }
}
