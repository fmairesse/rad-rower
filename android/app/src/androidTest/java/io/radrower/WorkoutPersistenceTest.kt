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
        withTimeout(10_000) {
            while (!predicate()) delay(20)
        }
    }

    @Test fun savesWorkoutSamplesAndRecoversAfterReopening() = runBlocking {
        val dao = database.workouts()
        val recorder = WorkoutRecorder(dao, scope)
        recorder.record(mapOf("sessionState" to 1, "distance" to 999))
        recorder.record(mapOf("sessionState" to 2, "elapsed" to 0, "distance" to 0))
        recorder.record(mapOf("watts" to 120, "cadence" to 24, "averagePace" to 123, "caloriesPerHour" to 800))
        recorder.record(mapOf("sessionState" to 3, "distance" to 100, "calories" to 10))
        recorder.record(mapOf("watts" to null))
        recorder.record(mapOf("sessionState" to 2, "elapsed" to 20))
        recorder.record(mapOf("sessionState" to 4, "distance" to 0, "calories" to 0))
        recorder.record(mapOf("sessionState" to 1))
        waitUntil { dao.workouts().singleOrNull()?.status == "completed" }
        val workout = dao.workouts().single()
        assertFalse(workout.partial)
        assertEquals(100.0, workout.distance!!, 0.0)
        assertEquals(10.0, workout.calories!!, 0.0)
        assertEquals(24.0, workout.averageCadence!!, 0.0)
        val samples = dao.samples(workout.id)
        assertEquals(6, samples.size)
        assertEquals(samples.size, workout.sampleCount)
        assertEquals((0..5).toList(), samples.map { it.sequence })
        val motion = JSONObject(samples[1].metricsJson)
        assertEquals(120, motion.getInt("watts"))
        assertFalse(motion.has("averagePace"))
        assertFalse(motion.has("caloriesPerHour"))
        assertTrue(JSONObject(samples[3].metricsJson).isNull("watts"))
        scope.cancel()
        database.close()
        database = Room.databaseBuilder(context, WorkoutDatabase::class.java, name).build()
        assertEquals(workout, database.workouts().workouts().single())
        assertEquals(samples, database.workouts().samples(workout.id))
    }

    @Test fun disconnectAndRecoveryPreserveInterruptedWorkouts() = runBlocking {
        val dao = database.workouts()
        val recorder = WorkoutRecorder(dao, scope)
        recorder.record(mapOf("sessionState" to 3, "elapsed" to 50))
        recorder.disconnect()
        waitUntil { dao.workouts().singleOrNull()?.status == "interrupted" }
        val first = dao.workouts().single()
        assertTrue(first.partial)
        assertNotNull(first.endedAt)
        recorder.record(mapOf("sessionState" to 2, "elapsed" to 0))
        waitUntil { dao.workouts().size == 2 }
        scope.cancel()
        dao.recoverInterrupted()
        val recovered = dao.workouts().single { it.id != first.id }
        assertEquals("interrupted", recovered.status)
        assertEquals(recovered.lastSampleAt, recovered.endedAt)
        assertEquals(1, dao.samples(recovered.id).size)
    }

    @Test fun invalidSampleRollsBackWorkoutUpdate() = runBlocking {
        val dao = database.workouts()
        val original = Workout("test", 100, 0, lastSampleAt = 100, lastSampleOffsetSeconds = 0, partial = false)
        dao.save(original, null)
        try {
            dao.save(original.copy(status = "completed"),
                WorkoutSample("missing", 0, 100, 0, "{}"))
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
        recorder.record(mapOf("sessionState" to 2, "elapsed" to 0))
        waitUntil { dao.workouts().isNotEmpty() }
        failingDao.failWrites = true
        recorder.record(mapOf("watts" to 120))
        val message = withTimeout(10_000) { recorder.error.first { it != null } }
        assertTrue(requireNotNull(message).contains("Workout storage failed"))
    }
}
