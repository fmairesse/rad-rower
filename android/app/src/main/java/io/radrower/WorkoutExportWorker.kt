package io.radrower

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class WorkoutExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(WORKOUT_ID)
        if (id == null) {
            Log.e(TAG, "Cannot export a workout without an ID")
            return Result.failure()
        }
        val app = applicationContext as RadrowerApplication
        try {
            val dao = app.database.workouts()
            val exported = exportPendingWorkout(dao, id) { workout, samples, segments ->
                check(HealthConnectClient.getSdkStatus(applicationContext) == HealthConnectClient.SDK_AVAILABLE) {
                    "Health Connect is unavailable or needs an update."
                }
                val client = HealthConnectClient.getOrCreate(applicationContext)
                HealthConnectExport.export(client, workout, samples, segments)
            }
            if (exported) app.exportEvents.tryEmit("Workout exported to Health Connect.")
            return Result.success()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.e(TAG, "Workout export failed; it will be retried", error)
            app.exportEvents.tryEmit("Workout export pending. Check Health Connect availability and permissions.")
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "WorkoutExportWorker"
        private const val WORKOUT_ID = "workoutId"

        fun enqueue(context: Context, id: String) {
            val request = OneTimeWorkRequestBuilder<WorkoutExportWorker>()
                .setInputData(workDataOf(WORKOUT_ID to id))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("workout-export:$id", ExistingWorkPolicy.KEEP, request)
                .result.get()
        }

        internal suspend fun exportPendingWorkout(
            dao: WorkoutDao,
            id: String,
            write: suspend (Workout, List<WorkoutSample>, List<Segment>) -> Unit,
        ): Boolean {
            val workout = dao.workout(id) ?: return false
            if (workout.exported || !HealthConnectExport.isEligible(workout)) return false
            write(workout, dao.samples(id), dao.segments(id))
            dao.markExported(id)
            return true
        }
    }
}
