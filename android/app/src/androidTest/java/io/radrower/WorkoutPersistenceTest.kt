package io.radrower

import androidx.room.Room
import android.database.sqlite.SQLiteDatabase
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
        val segments = dao.segments(workout.id)
        assertEquals(listOf("rowing", "pause", "rowing"), segments.map { it.type })
        assertEquals(listOf(0, 1, 2), segments.map { it.sequence })
        assertEquals(workout.startedAtEpochMs, segments.first().startedAtEpochMs)
        assertEquals(workout.endedAtEpochMs, segments.last().endedAtEpochMs)
        assertEquals(segments[0].endedAtEpochMs, segments[1].startedAtEpochMs)
        assertEquals(segments[1].endedAtEpochMs, segments[2].startedAtEpochMs)
        scope.cancel()
        database.close()
        database = Room.databaseBuilder(context, WorkoutDatabase::class.java, name).build()
        assertEquals(workout, database.workouts().workouts().single())
        assertEquals(samples, database.workouts().samples(workout.id))
        assertEquals(segments, database.workouts().segments(workout.id))
    }

    @Test fun migratesExistingWorkoutsAsExported() = runBlocking {
        database.close()
        context.deleteDatabase(name)
        val schema = JSONObject(
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("io.radrower.WorkoutDatabase/1.json").bufferedReader().use { it.readText() },
        ).getJSONObject("database")
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices")
            for (j in 0 until (indices?.length() ?: 0)) {
                raw.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
        raw.execSQL(
            """INSERT INTO workouts (id, startedAtEpochMs, startedOffsetS, lastSampleAtEpochMs,
                lastSampleOffsetS, status, partial, sampleCount, activeMs, cadenceTotalSpm, cadenceSampleCount)
                VALUES ('old', 100, 0, 200, 0, 'completed', 0, 0, 0, 0, 0)""",
        )
        raw.version = 1
        raw.close()
        database = Room.databaseBuilder(context, WorkoutDatabase::class.java, name)
            .addMigrations(WorkoutDatabase.MIGRATION_1_2).build()
        assertTrue(database.workouts().workout("old")!!.exported)
        assertTrue(database.workouts().pendingExports().isEmpty())
    }

    @Test fun exportsOnlyPendingEligibleWorkoutsAndMarksOnlyAfterSuccess() = runBlocking {
        val dao = database.workouts()
        val completed = Workout(
            "pending", 100, 0, endedAtEpochMs = 200, lastSampleAtEpochMs = 200,
            lastSampleOffsetS = 0, status = "completed", partial = false,
        )
        dao.upsertWorkout(completed)
        dao.upsertWorkout(completed.copy(id = "exported", exported = true))
        dao.upsertWorkout(completed.copy(id = "partial", partial = true))
        dao.upsertWorkout(completed.copy(id = "interrupted", status = "interrupted"))
        dao.upsertWorkout(completed.copy(id = "invalid", endedAtEpochMs = 100))
        assertEquals(listOf("pending"), dao.pendingExports().map { it.id })
        try {
            WorkoutExportWorker.exportPendingWorkout(dao, "pending") { _, _, _ -> error("write failed") }
            fail("Expected export failure")
        } catch (_: IllegalStateException) {
            assertFalse(dao.workout("pending")!!.exported)
        }
        var writes = 0
        assertTrue(WorkoutExportWorker.exportPendingWorkout(dao, "pending") { workout, _, _ ->
            assertEquals("pending", workout.id)
            writes++
        })
        assertTrue(dao.workout("pending")!!.exported)
        assertFalse(WorkoutExportWorker.exportPendingWorkout(dao, "pending") { _, _, _ -> writes++ })
        assertFalse(WorkoutExportWorker.exportPendingWorkout(dao, "partial") { _, _, _ -> writes++ })
        assertEquals(1, writes)
    }

    @Test fun signalsCompletionAfterSaving() = runBlocking {
        val dao = database.workouts()
        var now = 1_000L
        val scheduled = kotlinx.coroutines.CompletableDeferred<Workout>()
        val recorder = WorkoutRecorder(dao, scope, clock = { now }, monotonicClock = { now }) {
            assertEquals("completed", dao.workout(it.id)!!.status)
            scheduled.complete(it)
        }
        recorder.recordMetrics("sessionState" to 1)
        now += 1_000
        recorder.recordMetrics("sessionState" to 2)
        now += 1_000
        recorder.recordMetrics("sessionState" to 4)
        val workout = withTimeout(10_000.milliseconds) { scheduled.await() }
        assertFalse(workout.exported)
        assertEquals(listOf(workout.id), dao.pendingExports().map { it.id })
    }

    @Test fun schedulingFailureDoesNotLoseCompletedWorkout() = runBlocking {
        val dao = database.workouts()
        var now = 1_000L
        val recorder = WorkoutRecorder(dao, scope, clock = { now }, monotonicClock = { now }) {
            error("scheduler unavailable")
        }
        recorder.recordMetrics("sessionState" to 1)
        now += 1_000
        recorder.recordMetrics("sessionState" to 2)
        now += 1_000
        recorder.recordMetrics("sessionState" to 4)
        val message = withTimeout(10_000.milliseconds) { recorder.error.first { it != null } }
        assertTrue(requireNotNull(message).contains("Workout saved, but export could not be scheduled"))
        assertEquals("completed", dao.workouts().single().status)
        assertEquals(1, dao.pendingExports().size)
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
        assertEquals("pause", dao.segments(first.id).single().type)
        assertEquals(first.endedAtEpochMs, dao.segments(first.id).single().endedAtEpochMs)
        recorder.recordMetrics("sessionState" to 2, "elapsedS" to 0)
        waitUntil { dao.workouts().size == 2 }
        scope.cancel()
        dao.recoverInterrupted()
        val recovered = dao.workouts().single { it.id != first.id }
        assertEquals("interrupted", recovered.status)
        assertEquals(recovered.lastSampleAtEpochMs, recovered.endedAtEpochMs)
        assertEquals(1, dao.samples(recovered.id).size)
        assertEquals(recovered.endedAtEpochMs, dao.segments(recovered.id).single().endedAtEpochMs)
    }

    @Test fun recordsRepeatedTransitionsAndFinishesWhilePaused() = runBlocking {
        val dao = database.workouts()
        var now = 1_700_000_000_000L
        val recorder = WorkoutRecorder(dao, scope, clock = { now }, monotonicClock = { now })
        fun send(state: Int?) {
            recorder.recordMetrics("sessionState" to state)
            now += 1_000
        }
        send(1)
        send(2)
        send(2)
        send(3)
        send(3)
        send(2)
        send(3)
        send(4)
        waitUntil { dao.workouts().singleOrNull()?.status == "completed" }
        val workout = dao.workouts().single()
        val segments = dao.segments(workout.id)
        assertEquals(listOf("rowing", "pause", "rowing", "pause"), segments.map { it.type })
        assertEquals(listOf(2_000L, 2_000L, 1_000L, 1_000L), segments.map { it.endedAtEpochMs!! - it.startedAtEpochMs })
        assertEquals(listOf(1_000L, 3_000L, 5_000L, 6_000L), segments.map { it.startedAtEpochMs - 1_700_000_000_000L })
        assertEquals(workout.endedAtEpochMs, segments.last().endedAtEpochMs)
    }

    @Test fun unknownStateDoesNotSplitTheCurrentSegment() = runBlocking {
        val dao = database.workouts()
        var now = 1_700_000_000_000L
        val recorder = WorkoutRecorder(dao, scope, clock = { now }, monotonicClock = { now })
        recorder.recordMetrics("sessionState" to 1)
        recorder.recordMetrics("sessionState" to 2)
        now += 1_000
        recorder.recordMetrics("sessionState" to 99)
        now += 1_000
        recorder.recordMetrics("sessionState" to 2)
        now += 1_000
        recorder.recordMetrics("sessionState" to 4)
        waitUntil { dao.workouts().singleOrNull()?.status == "completed" }
        val workout = dao.workouts().single()
        assertEquals(listOf(Segment(workout.id, 0, "rowing", workout.startedAtEpochMs, workout.endedAtEpochMs)), dao.segments(workout.id))
    }

    @Test fun invalidSegmentRollsBackWorkoutAndSample() = runBlocking {
        val dao = database.workouts()
        val workout = Workout("test", 100, 0, lastSampleAtEpochMs = 100, lastSampleOffsetS = 0, partial = false)
        dao.save(workout, null)
        val sample = WorkoutSample(
            workoutId = workout.id, sequence = 0, timestampEpochMs = 101,
            offsetS = 0, sessionState = 2, resistanceLevel = 1,
        )
        try {
            dao.save(workout.copy(sampleCount = 1), sample, listOf(Segment("missing", 0, "rowing", 100)))
            fail("Expected a foreign-key failure")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            assertEquals(0, dao.workouts().single().sampleCount)
            assertTrue(dao.samples(workout.id).isEmpty())
            assertTrue(dao.segments(workout.id).isEmpty())
        }
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
            override suspend fun upsertSegment(segment: Segment) = dao.upsertSegment(segment)
            override fun observeWorkouts() = dao.observeWorkouts()
            override suspend fun workouts() = dao.workouts()
            override suspend fun workout(id: String) = dao.workout(id)
            override suspend fun pendingExports() = dao.pendingExports()
            override suspend fun markExported(id: String) = dao.markExported(id)
            override suspend fun samples(id: String) = dao.samples(id)
            override suspend fun segments(id: String) = dao.segments(id)
            override suspend fun closeRecoveredSegments() = dao.closeRecoveredSegments()
            override suspend fun markInterrupted() = dao.markInterrupted()
            override suspend fun recoverInterrupted() = dao.recoverInterrupted()
            override suspend fun save(workout: Workout, sample: WorkoutSample?, segments: List<Segment>) {
                if (failWrites) error("Storage unavailable")
                dao.save(workout, sample, segments)
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
