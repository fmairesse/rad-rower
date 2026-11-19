package io.radrower

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class HealthSyncExperimentTest {
    @Test
    fun i1HasOneConsistentRecordPerMetric() {
        val records = HealthSyncExperiment.records()
        assertEquals(4, records.size)
        val exercise = records.filterIsInstance<ExerciseSessionRecord>().single()
        val distance = records.filterIsInstance<DistanceRecord>().single()
        val power = records.filterIsInstance<PowerRecord>().single()
        val speed = records.filterIsInstance<SpeedRecord>().single()
        val start = Instant.parse("2026-11-19T05:00:00Z")
        val end = Instant.parse("2026-11-19T05:10:00Z")
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, exercise.exerciseType)
        assertEquals("Radrower experiment I1", exercise.title)
        assertEquals("radrower-experiment:I1:exercise", exercise.metadata.clientRecordId)
        assertEquals("radrower-experiment:I1:distance", distance.metadata.clientRecordId)
        assertEquals("radrower-experiment:I1:power", power.metadata.clientRecordId)
        assertEquals("radrower-experiment:I1:speed", speed.metadata.clientRecordId)
        records.forEach { assertEquals(1L, it.metadata.clientRecordVersion) }
        assertEquals(listOf(start, start, start, start), listOf(
            exercise.startTime, distance.startTime, power.startTime, speed.startTime,
        ))
        assertEquals(listOf(end, end, end, end), listOf(
            exercise.endTime, distance.endTime, power.endTime, speed.endTime,
        ))
        assertEquals(List(4) { ZoneOffset.ofHours(1) }, listOf(
            exercise.startZoneOffset, distance.startZoneOffset, power.startZoneOffset, speed.startZoneOffset,
        ))
        assertEquals(List(4) { ZoneOffset.ofHours(1) }, listOf(
            exercise.endZoneOffset, distance.endZoneOffset, power.endZoneOffset, speed.endZoneOffset,
        ))
        assertEquals(600, speed.samples.size)
        assertEquals(600, power.samples.size)
        speed.samples.forEachIndexed { second, sample ->
            assertEquals(start.plusSeconds(second.toLong()), sample.time)
            assertEquals(sample.time, power.samples[second].time)
            assertEquals(2.0 + 0.005 * second, sample.speed.inMetersPerSecond, 1e-9)
            assertEquals(100.0 + second, power.samples[second].power.inWatts, 1e-9)
        }
        assertEquals(2_098.5, distance.distance.inMeters, 1e-9)
        assertEquals(speed.samples.sumOf { it.speed.inMetersPerSecond }, distance.distance.inMeters, 1e-9)
    }

    @Test
    fun insertI1() {
        assumeTrue(
            "Pass -e runExperiment true to write I1 to Health Connect",
            InstrumentationRegistry.getArguments().getString("runExperiment") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            "Health Connect is unavailable."
        }
        runBlocking { HealthSyncExperiment.insert(HealthConnectClient.getOrCreate(context)) }
    }
}
