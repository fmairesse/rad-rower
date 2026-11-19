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
        distance: Number? = null,
        speed: Number? = null,
    ): WorkoutSample {
        val entries = buildList {
            sessionState?.let { add("\"sessionState\":$it") }
            watts?.let {
                val value = it.toDouble()
                add("\"watts\":${if (value.isFinite()) value else "\"NaN\""}")
            }
            distance?.let {
                val value = it.toDouble()
                add("\"distance\":${if (value.isFinite()) value else "\"NaN\""}")
            }
            speed?.let {
                val value = it.toDouble()
                add("\"speed\":${if (value.isFinite()) value else "\"NaN\""}")
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
        val samples = listOf(sample(0, workout.startedAt, sessionState = 2, watts = 100, speed = 18))
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
        val workout = workout(distance = 150.0)
        val samples = listOf(
            sample(0, workout.startedAt, sessionState = 2, distance = 0),
            sample(1, workout.startedAt + 1_000, distance = 100),
            sample(2, workout.startedAt + 2_000, sessionState = 3, distance = 0),
            sample(3, workout.startedAt + 3_000, distance = 0),
            sample(4, workout.startedAt + 4_000, sessionState = 2, distance = 100),
            sample(5, workout.startedAt + 5_000, distance = 150),
            sample(6, workout.endedAt!!, sessionState = 4, distance = 0),
        )

        val records = HealthConnectExport.records(workout, samples)
        val distances = records.filterIsInstance<DistanceRecord>()

        val distance = distances.single()
        assertEquals(workout.startedAt, distance.startTime.toEpochMilli())
        assertEquals(workout.endedAt, distance.endTime.toEpochMilli())
        assertEquals(150.0, distance.distance.inMeters, 0.0)
        assertEquals("radrower:workout-id:distance", distance.metadata.clientRecordId)
        assertEquals(6L, distance.metadata.clientRecordVersion)
        assertEquals(3_600, distance.startZoneOffset!!.totalSeconds)
        assertEquals(3_600, distance.endZoneOffset!!.totalSeconds)
        assertTrue("android.permission.health.WRITE_DISTANCE" in HealthConnectExport.permissionsFor(workout, samples))
    }

    @Test
    fun exportsStoredDistanceWithoutActiveSamples() {
        val workout = workout(distance = 500.0)
        val samples = listOf(
            sample(0, workout.startedAt, sessionState = 3, distance = 500),
            sample(1, workout.startedAt + 1_000, distance = 550),
        )

        assertEquals(500.0, HealthConnectExport.records(workout, samples).filterIsInstance<DistanceRecord>().single().distance.inMeters, 0.0)
        assertTrue("android.permission.health.WRITE_DISTANCE" in HealthConnectExport.permissionsFor(workout, samples))
    }

    @Test
    fun omitsUnavailableOrNonpositiveTotalsAndTheirPermissions() {
        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { distance ->
            val workout = workout(distance = distance, calories = 0.0)
            assertEquals(1, HealthConnectExport.records(workout).size)
            assertEquals(
                setOf("android.permission.health.WRITE_EXERCISE"),
                HealthConnectExport.permissionsFor(workout),
            )
        }
    }

    @Test
    fun exportsMeasuredPowerSamplesOnlyWhileSessionIsActive() {
        val workout = workout(distance = null)
        val samples = listOf(
            sample(0, workout.startedAt, sessionState = 2, watts = 0),
            sample(1, workout.startedAt + 1_000, watts = 120),
            sample(2, workout.startedAt + 2_000, sessionState = 3, watts = 0),
            sample(3, workout.startedAt + 3_000, watts = 250),
            sample(4, workout.startedAt + 4_000, sessionState = 2, watts = 140),
            sample(5, workout.endedAt!!, sessionState = 4, watts = 0),
            sample(6, workout.endedAt + 1_000, sessionState = 2, watts = 180),
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
    fun exportsActiveSpeedSamplesInMetersPerSecond() {
        val workout = workout(distance = null, calories = 0.0)
        val samples = listOf(
            sample(0, workout.startedAt - 1_000, sessionState = 2, speed = 36),
            sample(1, workout.startedAt, sessionState = 2, speed = 0),
            sample(2, workout.startedAt + 1_000, speed = 18),
            sample(3, workout.startedAt + 2_000, sessionState = 3, speed = 30),
            sample(4, workout.startedAt + 3_000, speed = 30),
            sample(5, workout.startedAt + 4_000, sessionState = 2, speed = 36),
            sample(6, workout.startedAt + 4_000, speed = 18),
            sample(7, workout.startedAt + 5_000, speed = Double.NaN),
            sample(8, workout.startedAt + 6_000, speed = -1),
            sample(9, workout.endedAt!!, sessionState = 4, speed = 36),
            sample(10, workout.endedAt + 1_000, sessionState = 2, speed = 36),
        )
        val speed = HealthConnectExport.records(workout, samples).filterIsInstance<SpeedRecord>().single()
        assertEquals(listOf(workout.startedAt, workout.startedAt + 1_000, workout.startedAt + 4_000),
            speed.samples.map { it.time.toEpochMilli() })
        assertEquals(listOf(0.0, 5.0, 5.0), speed.samples.map { it.speed.inMetersPerSecond })
        assertEquals("radrower:workout-id:speed", speed.metadata.clientRecordId)
        assertEquals(Instant.ofEpochMilli(workout.startedAt), speed.startTime)
        assertEquals(Instant.ofEpochMilli(workout.endedAt), speed.endTime)
        assertEquals(setOf("android.permission.health.WRITE_EXERCISE", "android.permission.health.WRITE_SPEED"),
            HealthConnectExport.permissionsFor(workout, samples))
    }

    @Test
    fun omitsSpeedAndPermissionWithoutActiveMeasurements() {
        val workout = workout(distance = null, calories = 0.0)
        val samples = listOf(
            sample(0, workout.startedAt, sessionState = 3, speed = 18),
            sample(1, workout.startedAt + 1_000, speed = 18),
            sample(2, workout.startedAt + 2_000, sessionState = 2, speed = -1),
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
        val workout = workout(distance = 100.0, calories = 0.0).copy(id = "test-${UUID.randomUUID()}")
        val samples = listOf(sample(0, workout.startedAt, sessionState = 2, watts = 100, speed = 18))
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
        assertFalse(HealthConnectExport.isEligible(workout(endedAt = null)))
        assertFalse(HealthConnectExport.isEligible(workout(endedAt = 1_700_000_000_000)))
    }
}
