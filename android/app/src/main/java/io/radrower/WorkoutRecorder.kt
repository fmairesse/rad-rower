package io.radrower

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.TimeZone
import java.util.UUID
import kotlin.math.max
import kotlin.math.round

internal class WorkoutRecorder(
    private val dao: WorkoutDao,
    scope: CoroutineScope,
) {
    private sealed interface Event {
        data class Metrics(
            val values: Map<String, Any?>,
            val timestamp: Long,
            val monotonic: Long,
            val offsetSeconds: Int,
        ) : Event
        data class Disconnect(val timestamp: Long, val offsetSeconds: Int) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError
    private var current: Workout? = null
    private var rowerState: Int? = null
    private var lastMonotonic: Long? = null

    init {
        scope.launch {
            try {
                dao.recoverInterrupted()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableError.value = "Workout storage unavailable: ${error.message ?: "database error"}"
                events.close()
                return@launch
            }
            for (event in events) {
                try {
                    when (event) {
                        is Event.Metrics -> record(event)
                        is Event.Disconnect -> interrupt(event)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableError.value = "Workout storage failed; some data may be missing: ${error.message ?: "database error"}"
                    if (event is Event.Disconnect) {
                        current = null
                        rowerState = null
                        lastMonotonic = null
                    }
                }
            }
        }
    }

    fun record(values: Map<String, Any?>) {
        val now = System.currentTimeMillis()
        events.trySend(Event.Metrics(values.toMap(), now, SystemClock.elapsedRealtime(), offsetAt(now)))
    }

    fun disconnect() {
        val now = System.currentTimeMillis()
        events.trySend(Event.Disconnect(now, offsetAt(now)))
    }

    private suspend fun record(event: Event.Metrics) {
        val state = event.values["sessionState"] as? Int
        val previous = current
        if (previous == null && state !in listOf(2, 3)) {
            if (state != null) rowerState = state
            return
        }
        val workout = previous ?: if (state == 2 || state == 3) {
            Workout(
                id = UUID.randomUUID().toString(),
                startedAt = event.timestamp,
                startedOffsetSeconds = event.offsetSeconds,
                lastSampleAt = event.timestamp,
                lastSampleOffsetSeconds = event.offsetSeconds,
                partial = state == 3 ||
                    (rowerState != 1 && ((event.values["elapsed"] as? Number)?.toDouble() ?: 0.0) > 2),
            )
        } else return
        val sampleState = state ?: rowerState
        val activeMillis = workout.activeMillis +
            if (previous != null && rowerState == 2 && lastMonotonic != null) {
                max(0, event.monotonic - requireNotNull(lastMonotonic))
            } else 0
        fun counter(key: String, old: Double?): Double? {
            val value = (event.values[key] as? Number)?.toDouble()
            return if (value != null && value.isFinite() && value >= 0) max(old ?: 0.0, value) else old
        }
        val cadence = (event.values["cadence"] as? Number)?.toDouble()
        val validCadence = cadence != null && cadence.isFinite() && cadence > 0 && sampleState == 2
        val cadenceTotal = workout.cadenceTotal + if (validCadence) requireNotNull(cadence) else 0.0
        val cadenceSamples = workout.cadenceSamples + if (validCadence) 1 else 0
        val distance = counter("distance", workout.distance)
        val elapsed = counter("elapsed", workout.elapsed)
        val averageWatts = (event.values["averageWatts"] as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it > 0 } ?: workout.averageWatts
        val hasAverage = !workout.partial &&
            distance != null && distance > 0 &&
            elapsed != null && elapsed > 0
        val finished = state in listOf(1, 4, 10)
        val next = workout.copy(
            lastSampleAt = event.timestamp,
            lastSampleOffsetSeconds = event.offsetSeconds,
            endedAt = if (finished) event.timestamp else null,
            endedOffsetSeconds = if (finished) event.offsetSeconds else null,
            status = if (finished) "completed" else "recording",
            sampleCount = workout.sampleCount + 1,
            activeMillis = activeMillis,
            distance = distance,
            calories = counter("calories", workout.calories),
            strokes = counter("strokes", workout.strokes),
            elapsed = elapsed,
            paused = counter("paused", workout.paused),
            averageWatts = averageWatts,
            cadenceTotal = cadenceTotal,
            cadenceSamples = cadenceSamples,
            averageCadence = if (cadenceSamples > 0) round2(cadenceTotal / cadenceSamples) else null,
            averageSpeed = if (hasAverage) round2(requireNotNull(distance) / requireNotNull(elapsed) * 3.6) else null,
            averagePace = if (hasAverage) round2(requireNotNull(elapsed) * 500 / requireNotNull(distance)) else null,
        )
        val json = JSONObject()
        for ((key, value) in event.values) {
            if (key == "averagePace" || key == "caloriesPerHour") continue
            when (value) {
                null -> json.put(key, JSONObject.NULL)
                is Number, is String -> json.put(key, value)
                else -> error("Unsupported workout metric: $key")
            }
        }
        val sample = WorkoutSample(workout.id, workout.sampleCount, event.timestamp, event.offsetSeconds, json.toString())
        dao.save(next, sample)
        current = if (finished) null else next
        rowerState = if (finished) null else state ?: rowerState
        lastMonotonic = if (finished) null else event.monotonic
    }

    private suspend fun interrupt(event: Event.Disconnect) {
        val workout = current ?: return
        dao.save(workout.copy(status = "interrupted", endedAt = event.timestamp,
            endedOffsetSeconds = event.offsetSeconds), null)
        current = null
        rowerState = null
        lastMonotonic = null
    }

    private fun offsetAt(timestamp: Long): Int = TimeZone.getDefault().getOffset(timestamp) / 1000
    private fun round2(value: Double): Double = round(value * 100) / 100
}
