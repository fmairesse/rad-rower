package io.radrower

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.junit.Test
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HealthConnectExportTest {
    private fun workout(
        status: String = "completed",
        partial: Boolean = false,
        endedAtEpochMs: Long? = 1_700_003_600_000,
        distanceM: Double? = 1_234.0,
        caloriesKcal: Double? = 42.0,
    ) = Workout(
        id = "workout-id",
        startedAtEpochMs = 1_700_000_000_000,
        startedOffsetS = 3_600,
        endedAtEpochMs = endedAtEpochMs,
        endedOffsetS = 3_600,
        lastSampleAtEpochMs = endedAtEpochMs ?: 1_700_000_000_000,
        lastSampleOffsetS = 3_600,
        status = status,
        partial = partial,
        distanceM = distanceM,
        caloriesKcal = caloriesKcal,
    )

    private var previousSampleState = SessionState.IN_PROGRESS.code

    private fun sample(
        sequence: Int,
        timestampEpochMs: Long,
        sessionState: Int? = null,
        powerW: Number? = null,
        distanceM: Number? = null,
        speedKmh: Number? = null,
        workoutId: String = "workout-id",
    ): WorkoutSample {
        if (sequence == 0) previousSampleState = SessionState.IN_PROGRESS.code
        sessionState?.let { previousSampleState = it }
        return WorkoutSample(
            workoutId = workoutId,
            sequence = sequence,
            timestampEpochMs = timestampEpochMs,
            offsetS = 3_600,
            sessionState = previousSampleState,
            powerW = powerW?.toDouble(),
            distanceM = distanceM?.toDouble(),
            resistanceLevel = 1,
            speedKmh = speedKmh?.toDouble(),
        )
    }

    @Test
    fun exportsRowingSessionAndAvailableActiveCalories() {
        val workout = workout()
        val records = HealthConnectExport.records(workout)

        assertEquals(2, records.size)
        val session = records.filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, session.exerciseType)
        assertEquals("Indoor rowing", session.title)
        assertTrue(session.laps.isEmpty())
        assertEquals("radrower:workout-id:exercise", session.metadata.clientRecordId)
        assertEquals(
            "radrower:workout-id:active-calories",
            records.filterIsInstance<ActiveCaloriesBurnedRecord>().single().metadata.clientRecordId,
        )
        assertEquals(
            setOf(
                "android.permission.health.WRITE_EXERCISE",
                "android.permission.health.WRITE_ACTIVE_CALORIES_BURNED",
            ),
            HealthConnectExport.permissionsFor(workout),
        )
    }

    @Test
    fun exportsInterpolatedOneSecondDistanceWindowsDuringActiveStretches() {
        val workout = workout(distanceM = 50.0)
        val samples = listOf(
            sample(0, workout.startedAtEpochMs, sessionState = 2, distanceM = 0),
            sample(1, workout.startedAtEpochMs + 2_500, distanceM = 25),
            sample(2, workout.startedAtEpochMs + 3_000, sessionState = 3, distanceM = 0),
            sample(3, workout.startedAtEpochMs + 4_000, distanceM = 0),
            sample(4, workout.startedAtEpochMs + 5_000, sessionState = 2, distanceM = 0),
            sample(5, workout.startedAtEpochMs + 7_000, distanceM = 30),
        )

        val distances = HealthConnectExport.records(workout, samples).filterIsInstance<DistanceRecord>()

        assertEquals(
            listOf(
                workout.startedAtEpochMs to workout.startedAtEpochMs + 1_000,
                workout.startedAtEpochMs + 1_000 to workout.startedAtEpochMs + 2_000,
                workout.startedAtEpochMs + 2_000 to workout.startedAtEpochMs + 2_500,
                workout.startedAtEpochMs + 5_000 to workout.startedAtEpochMs + 6_000,
                workout.startedAtEpochMs + 6_000 to workout.startedAtEpochMs + 7_000,
            ),
            distances.map { it.startTime.toEpochMilli() to it.endTime.toEpochMilli() },
        )
        assertEquals(listOf(10.0, 10.0, 5.0, 15.0, 15.0), distances.map { it.distance.inMeters })
        assertEquals(55.0, distances.sumOf { it.distance.inMeters }, 1e-9)
        assertEquals("radrower:workout-id:distance", distances.first().metadata.clientRecordId)
        assertEquals(7L, distances.first().metadata.clientRecordVersion)
        assertEquals(3_600, distances.first().startZoneOffset!!.totalSeconds)
        assertEquals(3_600, distances.last().endZoneOffset!!.totalSeconds)
        assertTrue("android.permission.health.WRITE_DISTANCE" in HealthConnectExport.permissionsFor(workout, samples))
    }

    @Test
    fun exportsPowerButNeverSpeedRecordsOrSpeedPermission() {
        val workout = workout(distanceM = null, caloriesKcal = 0.0)
        val samples = listOf(
            sample(0, workout.startedAtEpochMs, sessionState = 2, powerW = 100, speedKmh = 18),
            sample(1, workout.startedAtEpochMs + 1_000, powerW = 150, speedKmh = 21.6),
        )
        val records = HealthConnectExport.records(workout, samples)

        assertEquals(1, records.filterIsInstance<PowerRecord>().size)
        assertTrue(records.none { it is SpeedRecord })
        assertEquals(
            setOf("android.permission.health.WRITE_EXERCISE", "android.permission.health.WRITE_POWER"),
            HealthConnectExport.permissionsFor(workout, samples),
        )
        assertFalse("android.permission.health.WRITE_SPEED" in HealthConnectExport.allWritePermissions)
    }

    @Test
    fun distanceSummaryWithoutActiveDistanceSamplesDoesNotCreateDistanceRecord() {
        val workout = workout(distanceM = 500.0)
        val samples = listOf(
            sample(0, workout.startedAtEpochMs, sessionState = 3, distanceM = 500),
            sample(1, workout.startedAtEpochMs + 1_000, distanceM = 550),
        )

        assertTrue(HealthConnectExport.records(workout, samples).none { it is DistanceRecord })
        assertFalse("android.permission.health.WRITE_DISTANCE" in HealthConnectExport.permissionsFor(workout, samples))
    }

    @Test
    fun omitsUnavailableOrNonpositiveTotalsAndTheirPermissions() {
        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { distance ->
            val workout = workout(distanceM = distance, caloriesKcal = 0.0)
            assertEquals(1, HealthConnectExport.records(workout).size)
            assertEquals(
                setOf("android.permission.health.WRITE_EXERCISE"),
                HealthConnectExport.permissionsFor(workout),
            )
        }
    }

    @Test
    fun writesProductionExportToHealthConnectWhenRequested() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("runExportIntegration") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE)
        val client = HealthConnectClient.getOrCreate(context)
        val workout = workout(distanceM = 100.0).copy(id = "test-${UUID.randomUUID()}")
        val samples = listOf(
            sample(0, workout.startedAtEpochMs, sessionState = 2, powerW = 100, distanceM = 0, workoutId = workout.id),
            sample(1, workout.startedAtEpochMs + 1_000, powerW = 100, distanceM = 100, workoutId = workout.id),
        )
        val prefix = "radrower:${workout.id}"
        runBlocking {
            check(client.permissionController.getGrantedPermissions().containsAll(
                HealthConnectExport.permissionsFor(workout, samples),
            ))
            try {
                HealthConnectExport.export(client, workout, samples)
            } finally {
                client.deleteRecords(ExerciseSessionRecord::class, emptyList(), listOf("$prefix:exercise"))
                client.deleteRecords(DistanceRecord::class, emptyList(), listOf("$prefix:distance"))
                client.deleteRecords(ActiveCaloriesBurnedRecord::class, emptyList(), listOf("$prefix:active-calories"))
                client.deleteRecords(PowerRecord::class, emptyList(), listOf("$prefix:power"))
            }
        }
    }

    @Test
    fun onlyCompleteNonPartialWorkoutsWithValidTimesCanBeExported() {
        assertTrue(HealthConnectExport.isEligible(workout()))
        assertFalse(HealthConnectExport.isEligible(workout(status = "interrupted")))
        assertFalse(HealthConnectExport.isEligible(workout(partial = true)))
        assertFalse(HealthConnectExport.isEligible(workout(endedAtEpochMs = null)))
        assertFalse(HealthConnectExport.isEligible(workout(endedAtEpochMs = 1_700_000_000_000)))
    }
}
