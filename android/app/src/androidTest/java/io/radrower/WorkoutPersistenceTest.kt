package io.radrower

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.milliseconds

@RunWith(AndroidJUnit4::class)
class WorkoutPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "workout-persistence-test.db"
    private lateinit var database: WorkoutDatabase
    private lateinit var scope: CoroutineScope

    @Before fun setup() {
        context.deleteDatabase(name)
        database = Room.databaseBuilder(context, WorkoutDatabase::class.java, name).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After fun cleanup() {
        scope.cancel()
        database.close()
        context.deleteDatabase(name)
    }

    private suspend fun waitUntil(predicate: suspend () -> Boolean) {
        withTimeout(10_000.milliseconds) {
            while (!predicate()) delay(20.milliseconds)
        }
    }

    private fun WorkoutRecorder.recordMetrics(vararg values: Pair<String, Any?>) {
        record(mapOf(*values, "resistanceLevel" to 1))
    }

    @Test fun savesWorkoutSamplesAndRecoversAfterReopening() = runBlocking {
        val dao = database.workouts()
        val recorder = WorkoutRecorder(dao, scope)
        recorder.recordMetrics("sessionState" to 1, "distanceM" to 999)
        recorder.recordMetrics("sessionState" to 2, "elapsedS" to 0, "distanceM" to 0)
        recorder.recordMetrics(
            "powerW" to 120,
            "cadenceSpm" to 24,
            "averageWatts" to 110,
            "paused" to 5,
            "caloriesPerHour" to 800,
            "averagePace" to 145,
        )
        recorder.recordMetrics("sessionState" to 3, "distanceM" to 100, "caloriesKcal" to 10)
        recorder.recordMetrics("powerW" to null)
        recorder.recordMetrics("sessionState" to 2, "elapsedS" to 20)
        recorder.recordMetrics("sessionState" to 4, "distanceM" to 0, "caloriesKcal" to 0)
        recorder.recordMetrics("sessionState" to 1)
        waitUntil { dao.workouts().singleOrNull()?.status == "completed" }
        val workout = dao.workouts().single()
        assertFalse(workout.partial)
        assertEquals(100.0, workout.distanceM!!, 0.0)
        assertEquals(10.0, workout.caloriesKcal!!, 0.0)
        assertEquals(24.0, workout.averageCadenceSpm!!, 0.0)
        val samples = dao.samples(workout.id)
        assertEquals(6, samples.size)
        assertEquals(samples.size, workout.sampleCount)
        assertEquals((0..5).toList(), samples.map { it.sequence })
        assertEquals(120.0, samples[1].powerW!!, 0.0)
        assertEquals(24.0, samples[1].cadenceSpm!!, 0.0)
        val unvalidated = JSONObject(requireNotNull(samples[1].unvalidatedMetricsJson))
        assertEquals(110, unvalidated.getInt("averageWatts"))
        assertEquals(5, unvalidated.getInt("paused"))
        assertEquals(800, unvalidated.getInt("caloriesPerHour"))
        assertEquals(145, unvalidated.getInt("averagePace"))
        assertTrue(samples.all { it.resistanceLevel == 1 })
        assertNull(samples[3].powerW)
        scope.cancel()
        database.close()
        database = Room.databaseBuilder(context, WorkoutDatabase::class.java, name).build()
        assertEquals(workout, database.workouts().workouts().single())
        assertEquals(samples, database.workouts().samples(workout.id))
    }

    @Test fun calculatesAverageSpeedAndPaceFromRowerElapsedTime() = runBlocking {
        val dao = database.workouts()
        val recorder = WorkoutRecorder(dao, scope)
        recorder.recordMetrics("sessionState" to 1)
        recorder.recordMetrics("sessionState" to 2, "elapsedS" to 100, "distanceM" to 0)
        recorder.recordMetrics("sessionState" to 3, "elapsedS" to 110, "distanceM" to 300)
        recorder.recordMetrics("elapsedS" to 110, "distanceM" to 0)
        recorder.recordMetrics("sessionState" to 2, "elapsedS" to 110, "distanceM" to 300)
        recorder.recordMetrics("elapsedS" to 120, "distanceM" to 500)
        recorder.recordMetrics("sessionState" to 4, "elapsedS" to 120, "distanceM" to 0)
        recorder.recordMetrics("sessionState" to 1)

        waitUntil { dao.workouts().singleOrNull()?.status == "completed" }
        val workout = dao.workouts().single()

        assertFalse(workout.partial)
        assertEquals(120.0, workout.elapsedS!!, 0.0)
        assertEquals(500.0, workout.distanceM!!, 0.0)
        assertEquals(15.0, workout.averageSpeedKmh!!, 0.0)
        assertEquals(120.0, workout.averagePaceSPer500M!!, 0.0)
    }

    @Test fun omitsAveragesWhenRecordingStartsMidWorkout() = runBlocking {
        val dao = database.workouts()
        val recorder = WorkoutRecorder(dao, scope)
        recorder.recordMetrics("sessionState" to 2, "elapsedS" to 100, "distanceM" to 300)
        recorder.recordMetrics("sessionState" to 2, "elapsedS" to 120, "distanceM" to 500)
        recorder.recordMetrics("sessionState" to 4, "elapsedS" to 120, "distanceM" to 500)
        recorder.recordMetrics("sessionState" to 1)

        waitUntil { dao.workouts().singleOrNull()?.status == "completed" }
        val workout = dao.workouts().single()

        assertTrue(workout.partial)
        assertNull(workout.averageSpeedKmh)
        assertNull(workout.averagePaceSPer500M)
    }

    @Test fun disconnectAndRecoveryPreserveInterruptedWorkouts() = runBlocking {
        val dao = database.workouts()
        val recorder = WorkoutRecorder(dao, scope)
        recorder.recordMetrics("sessionState" to 3, "elapsedS" to 50)
        recorder.disconnect()
        waitUntil { dao.workouts().singleOrNull()?.status == "interrupted" }
        val first = dao.workouts().single()
        assertTrue(first.partial)
        assertNotNull(first.endedAtEpochMs)
        recorder.recordMetrics("sessionState" to 2, "elapsedS" to 0)
        waitUntil { dao.workouts().size == 2 }
        scope.cancel()
        dao.recoverInterrupted()
        val recovered = dao.workouts().single { it.id != first.id }
        assertEquals("interrupted", recovered.status)
        assertEquals(recovered.lastSampleAtEpochMs, recovered.endedAtEpochMs)
        assertEquals(1, dao.samples(recovered.id).size)
    }

    @Test fun invalidSampleRollsBackWorkoutUpdate() = runBlocking {
        val dao = database.workouts()
        val original = Workout("test", 100, 0, lastSampleAtEpochMs = 100, lastSampleOffsetS = 0, partial = false)
        dao.save(original, null)
        try {
            dao.save(
                original.copy(status = "completed"),
                WorkoutSample(
                    workoutId = "missing",
                    sequence = 0,
                    timestampEpochMs = 100,
                    offsetS = 0,
                    sessionState = SessionState.IDLE.code,
                    resistanceLevel = 1,
                ),
            )
            fail("Expected a foreign-key failure")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            assertEquals("recording", dao.workouts().single().status)
        }
    }

    @Test fun storageFailureIsReported() = runBlocking {
        val dao = database.workouts()
        val failingDao = object : WorkoutDao() {
            @Volatile var failWrites = false
            override suspend fun upsertWorkout(workout: Workout) = dao.upsertWorkout(workout)
            override suspend fun insertSample(sample: WorkoutSample) = dao.insertSample(sample)
            override fun observeWorkouts() = dao.observeWorkouts()
            override suspend fun workouts() = dao.workouts()
            override suspend fun samples(id: String) = dao.samples(id)
            override suspend fun recoverInterrupted() = dao.recoverInterrupted()
            override suspend fun save(workout: Workout, sample: WorkoutSample?) {
                if (failWrites) error("Storage unavailable")
                dao.save(workout, sample)
            }
        }
        val recorder = WorkoutRecorder(failingDao, scope)
        recorder.recordMetrics("sessionState" to 2, "elapsedS" to 0)
        waitUntil { dao.workouts().isNotEmpty() }
        failingDao.failWrites = true
        recorder.recordMetrics("powerW" to 120)
        val message = withTimeout(10_000.milliseconds) { recorder.error.first { it != null } }
        assertTrue(requireNotNull(message).contains("Workout storage failed"))
    }
}
