package io.radrower

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.ZoneOffset

/**
 * Run with argument `-e runWithExperiment` to save records on the device:
 * 
 * ```
 * gradlew :app:connectedDebugAndroidTest \
 *  -Pandroid.testInstrumentationRunnerArguments.class=io.radrower.HealthSyncExperimentTest \
 *  -Pandroid.testInstrumentationRunnerArguments.runExperiment=true
 * ```
 * 
 * On Android 14+, grant permissions:
 * ```
 * adb shell pm grant io.radrower android.permission.health.WRITE_EXERCISE
 * adb shell pm grant io.radrower android.permission.health.WRITE_POWER
 * adb shell pm grant io.radrower android.permission.health.WRITE_SPEED
 * adb shell pm grant io.radrower android.permission.health.WRITE_DISTANCE
 * ```
 * On Android 13-, grant permissions via Health Connect.
 */
@RunWith(AndroidJUnit4::class)
class HealthSyncExperimentTest {
    @Test
    fun hasOneConsistentRecordPerMetric() {
        val records = HealthSyncExperiment.records()
        assertEquals(4, records.size)
        val exercise = records.filterIsInstance<ExerciseSessionRecord>().single()
        val distance = records.filterIsInstance<DistanceRecord>().single()
        val power = records.filterIsInstance<PowerRecord>().single()
        val speed = records.filterIsInstance<SpeedRecord>().single()
        val start = HealthSyncExperiment.start
        val pauseStart = HealthSyncExperiment.pauseStart
        val pauseEnd = HealthSyncExperiment.pauseEnd
        val end = HealthSyncExperiment.end
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, exercise.exerciseType)
        assertEquals("Radrower experiment I1", exercise.title)
        assertEquals("radrower-experiment:I1:exercise", exercise.metadata.clientRecordId)
        assertEquals("radrower-experiment:I1:distance", distance.metadata.clientRecordId)
        assertEquals("radrower-experiment:I1:power", power.metadata.clientRecordId)
        assertEquals("radrower-experiment:I1:speed", speed.metadata.clientRecordId)
        records.forEach { assertEquals(4L, it.metadata.clientRecordVersion) }
        assertEquals(
            listOf(
                Triple(start, pauseStart, ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE),
                Triple(pauseStart, pauseEnd, ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE),
                Triple(pauseEnd, end, ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE),
            ),
            exercise.segments.map { Triple(it.startTime, it.endTime, it.segmentType) },
        )
        val laps = exercise.laps
        assertEquals(5, laps.size)
        val lapEndsNanos = listOf(
            200_166_666_667L, 408_564_171_123L, 532_050_458_716L, 640_091_836_735L, 660_000_000_000L,
        )
        laps.forEachIndexed { index, lap ->
            assertEquals(if (index == 0) start else laps[index - 1].endTime, lap.startTime)
            assertTrue(kotlin.math.abs(Duration.between(start.plusNanos(lapEndsNanos[index]), lap.endTime).toNanos()) <= 1_000)
            assertEquals(if (index < 4) 500.0 else 98.5, requireNotNull(lap.length).inMeters, 1e-8)
        }
        assertEquals(end, laps.last().endTime)
        assertEquals(distance.distance.inMeters, laps.sumOf { requireNotNull(it.length).inMeters }, 1e-8)
        assertEquals(
            listOf(start, start, start, start),
            listOf(
                exercise.startTime,
                distance.startTime,
                power.startTime,
                speed.startTime,
            ),
        )
        assertEquals(
            listOf(end, end, end, end),
            listOf(
                exercise.endTime,
                distance.endTime,
                power.endTime,
                speed.endTime,
            ),
        )
        assertEquals(
            List(4) { ZoneOffset.ofHours(1) },
            listOf(
                exercise.startZoneOffset,
                distance.startZoneOffset,
                power.startZoneOffset,
                speed.startZoneOffset,
            ),
        )
        assertEquals(
            List(4) { ZoneOffset.ofHours(1) },
            listOf(
                exercise.endZoneOffset,
                distance.endZoneOffset,
                power.endZoneOffset,
                speed.endZoneOffset,
            ),
        )
        assertEquals(600, speed.samples.size)
        assertEquals(600, power.samples.size)
        speed.samples.forEachIndexed { second, sample ->
            val expectedTime =
                if (second < 300) {
                    start.plusSeconds(second.toLong())
                } else {
                    pauseEnd.plusSeconds((second - 300).toLong())
                }
            assertEquals(expectedTime, sample.time)
            assertEquals(sample.time, power.samples[second].time)
            assertEquals(2.0 + 0.005 * second, sample.speed.inMetersPerSecond, 1e-9)
            assertEquals(100.0 + second, power.samples[second].power.inWatts, 1e-9)
        }
        assertEquals(pauseStart.minusSeconds(1), speed.samples[299].time)
        assertEquals(pauseEnd, speed.samples[300].time)
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
