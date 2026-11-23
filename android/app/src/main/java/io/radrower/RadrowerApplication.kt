package io.radrower

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow

internal class RadrowerApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val database by lazy { WorkoutDatabase.create(this) }
    val recorder by lazy {
        WorkoutRecorder(
            database.workouts(),
            scope,
            onShortWorkout = { toastEvents.tryEmit("Short workout not saved") },
            onCompleted = { enqueuePendingExports() },
        )
    }

    suspend fun enqueuePendingExports() {
        database.workouts().pendingExports().forEach { WorkoutExportWorker.enqueue(this, it.id) }
    }
}
