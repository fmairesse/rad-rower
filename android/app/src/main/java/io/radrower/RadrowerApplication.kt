package io.radrower

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal class RadrowerApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { WorkoutDatabase.create(this) }
    val recorder by lazy { WorkoutRecorder(database.workouts(), scope) }
}
