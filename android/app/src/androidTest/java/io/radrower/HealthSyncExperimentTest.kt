package io.radrower

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.meters
import androidx.health.connect.client.units.watts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.cos
import kotlin.math.roundToLong

/**
 * Run with argument `runExperiment=true` to save records on the device:
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
 * adb shell pm grant io.radrower android.permission.health.WRITE_DISTANCE
 * adb shell pm grant io.radrower android.permission.health.WRITE_EXERCISE_ROUTE
 * ```
 * On Android 13-, grant permissions via Health Connect.
 */
@RunWith(AndroidJUnit4::class)
class HealthSyncExperimentTest {
    @Test
    fun exportToHealthConnect() {
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

internal object HealthSyncExperiment {
    val start: Instant = Instant.parse("2026-11-21T01:20:00Z")
     val pauseStart: Instant = start.plusSeconds(300)
     val pauseEnd: Instant = pauseStart.plusSeconds(60)
    val end: Instant = pauseEnd.plusSeconds(300)
    private val offset = ZoneOffset.ofHours(1)
    private const val SAMPLE_COUNT = 600

    private fun metadata(type: String) =
        Metadata.activelyRecorded(
            device = Device(type = Device.TYPE_PHONE),
            clientRecordId = "radrower-experiment:I1:$type",
            clientRecordVersion = if (type == "exercise" || type == "power") 6 else 5,
        )

    fun records(): List<Record> {
        fun sampleTime(activeSecond: Int): Instant =
            if (activeSecond < 300) start.plusSeconds(activeSecond.toLong())
            else pauseEnd.plusSeconds((activeSecond - 300).toLong())
        val powerSamples =
            (0 until SAMPLE_COUNT).map { second ->
                PowerRecord.Sample(sampleTime(second), (100.0 + second).watts)
            }
        val distanceRecords =
            (0 until SAMPLE_COUNT).map { second ->
                DistanceRecord(
                    startTime = sampleTime(second),
                    startZoneOffset = offset,
                    endTime = sampleTime(second + 1),
                    endZoneOffset = offset,
                    distance = (2.0 + 0.005 * second).meters,
                    metadata = metadata("distance:$second"),
                )
            }
        return listOf<Record>(
            ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = offset,
                endTime = end,
                endZoneOffset = offset,
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
                title = "Radrower experiment I1",
                segments = listOf(
                    ExerciseSegment(start, pauseStart, ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE),
                    ExerciseSegment(pauseStart, pauseEnd, ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE),
                    ExerciseSegment(pauseEnd, end, ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE),
                ),
                metadata = metadata("exercise"),
            ),
            PowerRecord(
                startTime = start,
                startZoneOffset = offset,
                endTime = end,
                endZoneOffset = offset,
                samples = powerSamples,
                metadata = metadata("power"),
            ),
        ).plus(distanceRecords)
    }

    suspend fun insert(client: HealthConnectClient) {
        val required =
            setOf(
                HealthPermission.getWritePermission(ExerciseSessionRecord::class),
                HealthPermission.getWritePermission(PowerRecord::class),
                HealthPermission.getWritePermission(DistanceRecord::class),
                HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
            )
        val missing = required - client.permissionController.getGrantedPermissions()
        check(missing.isEmpty()) { "Missing permissions: $missing. Grant them in Health Connect." }
        client.insertRecords(records())
    }
}
