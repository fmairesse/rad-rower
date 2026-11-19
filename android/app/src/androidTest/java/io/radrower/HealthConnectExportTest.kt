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
import java.time.Instant
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
    ): WorkoutSample {
        if (sequence == 0) previousSampleState = SessionState.IN_PROGRESS.code
        sessionState?.let { previousSampleState = it }
        return WorkoutSample(
            workoutId = "workout-id",
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
    fun exportsRowingSessionAndAvailableMeasuredTotals() {
        val workout = workout()
        val records = HealthConnectExport.records(workout)

        assertEquals(3, records.size)
        val session = records[0] as ExerciseSessionRecord
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, session.exerciseType)
        assertEquals(Instant.ofEpochMilli(workout.startedAtEpochMs), session.startTime)
        assertEquals(Instant.ofEpochMilli(requireNotNull(workout.endedAtEpochMs)), session.endTime)
        assertEquals("Indoor rowing", session.title)
        assertEquals("radrower:workout-id:exercise", session.metadata.clientRecordId)
        assertEquals(
            "radrower:workout-id:active-calories",
            records.filterIsInstance<ActiveCaloriesBurnedRecord>().single().metadata.clientRecordId,
        )
        assertEquals(
            setOf(
                "android.permission.health.WRITE_EXERCISE",
                "android.permission.health.WRITE_ACTIVE_CALORIES_BURNED",
                "android.permission.health.WRITE_DISTANCE",
            ),
            HealthConnectExport.permissionsFor(workout),
        )
    }

    @Test
    fun exportsAllAvailableMetricsTogether() {
        val workout = workout()
        val samples = listOf(sample(0, workout.startedAtEpochMs, sessionState = 2, powerW = 100, speedKmh = 18))
        val records = HealthConnectExport.records(workout, samples)

        assertEquals(5, records.size)
        assertEquals(1, records.filterIsInstance<ExerciseSessionRecord>().size)
        assertEquals(1, records.filterIsInstance<DistanceRecord>().size)
        assertEquals(1, records.filterIsInstance<ActiveCaloriesBurnedRecord>().size)
        assertEquals(1, records.filterIsInstance<PowerRecord>().size)
        assertEquals(1, records.filterIsInstance<SpeedRecord>().size)
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
    fun exportsOneSessionDistanceUsingStoredTotal() {
        val workout = workout(distanceM = 150.0)
        val samples = listOf(
            sample(0, workout.startedAtEpochMs, sessionState = 2, distanceM = 0),
            sample(1, workout.startedAtEpochMs + 1_000, distanceM = 100),
            sample(2, workout.startedAtEpochMs + 2_000, sessionState = 3, distanceM = 0),
            sample(3, workout.startedAtEpochMs + 3_000, distanceM = 0),
            sample(4, workout.startedAtEpochMs + 4_000, sessionState = 2, distanceM = 100),
            sample(5, workout.startedAtEpochMs + 5_000, distanceM = 150),
            sample(6, workout.endedAtEpochMs!!, sessionState = 4, distanceM = 0),
        )

        val records = HealthConnectExport.records(workout, samples)
        val distances = records.filterIsInstance<DistanceRecord>()

        val distance = distances.single()
        assertEquals(workout.startedAtEpochMs, distance.startTime.toEpochMilli())
        assertEquals(workout.endedAtEpochMs, distance.endTime.toEpochMilli())
        assertEquals(150.0, distance.distance.inMeters, 0.0)
        assertEquals("radrower:workout-id:distance", distance.metadata.clientRecordId)
        assertEquals(6L, distance.metadata.clientRecordVersion)
        assertEquals(3_600, distance.startZoneOffset!!.totalSeconds)
        assertEquals(3_600, distance.endZoneOffset!!.totalSeconds)
        assertTrue("android.permission.health.WRITE_DISTANCE" in HealthConnectExport.permissionsFor(workout, samples))
    }

    @Test
    fun exportsStoredDistanceWithoutActiveSamples() {
        val workout = workout(distanceM = 500.0)
        val samples = listOf(
            sample(0, workout.startedAtEpochMs, sessionState = 3, distanceM = 500),
            sample(1, workout.startedAtEpochMs + 1_000, distanceM = 550),
        )

        assertEquals(500.0, HealthConnectExport.records(workout, samples).filterIsInstance<DistanceRecord>().single().distance.inMeters, 0.0)
        assertTrue("android.permission.health.WRITE_DISTANCE" in HealthConnectExport.permissionsFor(workout, samples))
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
    fun exportsMeasuredPowerSamplesOnlyWhileSessionIsActive() {
        val workout = workout(distanceM = null)
        val samples = listOf(
            sample(0, workout.startedAtEpochMs, sessionState = 2, powerW = 0),
            sample(1, workout.startedAtEpochMs + 1_000, powerW = 120),
            sample(2, workout.startedAtEpochMs + 2_000, sessionState = 3, powerW = 0),
            sample(3, workout.startedAtEpochMs + 3_000, powerW = 250),
            sample(4, workout.startedAtEpochMs + 4_000, sessionState = 2, powerW = 140),
            sample(5, workout.endedAtEpochMs!!, sessionState = 4, powerW = 0),
            sample(6, workout.endedAtEpochMs + 1_000, sessionState = 2, powerW = 180),
            sample(7, workout.startedAtEpochMs + 5_000, sessionState = 2, powerW = Double.NaN),
            sample(8, workout.startedAtEpochMs + 6_000, sessionState = 2, powerW = -1),
        )

        val records = HealthConnectExport.records(workout, samples)
        val power = records.filterIsInstance<PowerRecord>().single()

        assertEquals(
            listOf(workout.startedAtEpochMs, workout.startedAtEpochMs + 1_000, workout.startedAtEpochMs + 4_000),
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
            sample(0, workout.startedAtEpochMs, sessionState = 3, powerW = 180),
            sample(1, workout.startedAtEpochMs + 1_000, powerW = 150),
        )

        assertTrue(HealthConnectExport.records(workout, samples).none { it is PowerRecord })
        assertFalse("android.permission.health.WRITE_POWER" in HealthConnectExport.permissionsFor(workout, samples))
    }

    @Test
    fun exportsActiveSpeedSamplesInMetersPerSecond() {
        val workout = workout(distanceM = null, caloriesKcal = 0.0)
        val samples = listOf(
            sample(0, workout.startedAtEpochMs - 1_000, sessionState = 2, speedKmh = 36),
            sample(1, workout.startedAtEpochMs, sessionState = 2, speedKmh = 0),
            sample(2, workout.startedAtEpochMs + 1_000, speedKmh = 18),
            sample(3, workout.startedAtEpochMs + 2_000, sessionState = 3, speedKmh = 30),
            sample(4, workout.startedAtEpochMs + 3_000, speedKmh = 30),
            sample(5, workout.startedAtEpochMs + 4_000, sessionState = 2, speedKmh = 36),
            sample(6, workout.startedAtEpochMs + 4_000, speedKmh = 18),
            sample(7, workout.startedAtEpochMs + 5_000, speedKmh = Double.NaN),
            sample(8, workout.startedAtEpochMs + 6_000, speedKmh = -1),
            sample(9, workout.endedAtEpochMs!!, sessionState = 4, speedKmh = 36),
            sample(10, workout.endedAtEpochMs + 1_000, sessionState = 2, speedKmh = 36),
        )
        val speed = HealthConnectExport.records(workout, samples).filterIsInstance<SpeedRecord>().single()
        assertEquals(listOf(workout.startedAtEpochMs, workout.startedAtEpochMs + 1_000, workout.startedAtEpochMs + 4_000),
            speed.samples.map { it.time.toEpochMilli() })
        assertEquals(listOf(0.0, 5.0, 5.0), speed.samples.map { it.speed.inMetersPerSecond })
        assertEquals("radrower:workout-id:speed", speed.metadata.clientRecordId)
        assertEquals(Instant.ofEpochMilli(workout.startedAtEpochMs), speed.startTime)
        assertEquals(Instant.ofEpochMilli(workout.endedAtEpochMs), speed.endTime)
        assertEquals(setOf("android.permission.health.WRITE_EXERCISE", "android.permission.health.WRITE_SPEED"),
            HealthConnectExport.permissionsFor(workout, samples))
    }

    @Test
    fun omitsSpeedAndPermissionWithoutActiveMeasurements() {
        val workout = workout(distanceM = null, caloriesKcal = 0.0)
        val samples = listOf(
            sample(0, workout.startedAtEpochMs, sessionState = 3, speedKmh = 18),
            sample(1, workout.startedAtEpochMs + 1_000, speedKmh = 18),
            sample(2, workout.startedAtEpochMs + 2_000, sessionState = 2, speedKmh = -1),
        )
        assertTrue(HealthConnectExport.records(workout, samples).none { it is SpeedRecord })
        assertFalse("android.permission.health.WRITE_SPEED" in HealthConnectExport.permissionsFor(workout, samples))
    }

    @Test
    fun writesProductionExportToHealthConnectWhenRequested() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("runExportIntegration") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE)
        val client = HealthConnectClient.getOrCreate(context)
        val workout = workout(distanceM = 100.0, caloriesKcal = 0.0).copy(id = "test-${UUID.randomUUID()}")
        val samples = listOf(sample(0, workout.startedAtEpochMs, sessionState = 2, powerW = 100, speedKmh = 18))
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
                client.deleteRecords(PowerRecord::class, emptyList(), listOf("$prefix:power"))
                client.deleteRecords(SpeedRecord::class, emptyList(), listOf("$prefix:speed"))
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
