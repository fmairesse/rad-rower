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
            val timestampEpochMs: Long,
            val monotonicMs: Long,
            val offsetS: Int,
        ) : Event

        data class Disconnect(
            val timestampEpochMs: Long,
            val offsetS: Int,
        ) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError
    private var current: Workout? = null
    private var rowerState: Int? = null
    private var lastMonotonicMs: Long? = null

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
                        lastMonotonicMs = null
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
        val workout =
            previous ?: if (state == 2 || state == 3) {
                Workout(
                    id = UUID.randomUUID().toString(),
                    startedAtEpochMs = event.timestampEpochMs,
                    startedOffsetS = event.offsetS,
                    lastSampleAtEpochMs = event.timestampEpochMs,
                    lastSampleOffsetS = event.offsetS,
                    partial =
                        state == 3 ||
                            (rowerState != 1 && ((event.values["elapsedS"] as? Number)?.toDouble() ?: 0.0) > 2),
                )
            } else {
                return
            }
        val sampleState = requireNotNull(state ?: rowerState)
        val activeMs =
            workout.activeMs +
                if (previous != null && rowerState == 2 && lastMonotonicMs != null) {
                    max(0, event.monotonicMs - requireNotNull(lastMonotonicMs))
                } else {
                    0
                }

        fun metric(key: String): Double? =
            (event.values[key] as? Number)
                ?.toDouble()
                ?.takeIf { it.isFinite() }

        fun counter(
            key: String,
            old: Double?,
        ): Double? {
            val value = metric(key)
            return if (value != null && value >= 0) max(old ?: 0.0, value) else old
        }

        val cadenceSpm = metric("cadenceSpm")
        val validCadence = cadenceSpm != null && cadenceSpm > 0 && sampleState == 2
        val cadenceTotalSpm = workout.cadenceTotalSpm + if (validCadence) cadenceSpm else 0.0
        val cadenceSampleCount = workout.cadenceSampleCount + if (validCadence) 1 else 0
        val distanceM = counter("distanceM", workout.distanceM)
        val elapsedS = counter("elapsedS", workout.elapsedS)
        val hasAverage =
            !workout.partial &&
                distanceM != null && distanceM > 0 &&
                elapsedS != null && elapsedS > 0
        val finished = state in listOf(1, 4)
        val resistanceLevel = (event.values["resistanceLevel"] as? Number)?.toInt()
        require(resistanceLevel != null && resistanceLevel in 1..24) {
            "Bluetooth telemetry must provide a numeric resistance level."
        }
        val next =
            workout.copy(
                lastSampleAtEpochMs = event.timestampEpochMs,
                lastSampleOffsetS = event.offsetS,
                endedAtEpochMs = if (finished) event.timestampEpochMs else null,
                endedOffsetS = if (finished) event.offsetS else null,
                status = if (finished) "completed" else "recording",
                sampleCount = workout.sampleCount + 1,
                activeMs = activeMs,
                distanceM = distanceM,
                caloriesKcal = counter("caloriesKcal", workout.caloriesKcal),
                strokesCount = counter("strokesCount", workout.strokesCount),
                elapsedS = elapsedS,
                cadenceTotalSpm = cadenceTotalSpm,
                cadenceSampleCount = cadenceSampleCount,
                averageCadenceSpm =
                    if (cadenceSampleCount > 0) round2(cadenceTotalSpm / cadenceSampleCount) else null,
                averageSpeedKmh = if (hasAverage) round2(distanceM / elapsedS * 3.6) else null,
                averagePaceSPer500M = if (hasAverage) round2(elapsedS * 500 / distanceM) else null,
            )
        val unvalidatedMetrics = JSONObject()
        listOf("averageWatts", "paused", "caloriesPerHour", "averagePace").forEach { key ->
            if (key in event.values) {
                when (val value = event.values[key]) {
                    null -> unvalidatedMetrics.put(key, JSONObject.NULL)
                    is Number -> unvalidatedMetrics.put(key, value)
                    else -> error("Unsupported unvalidated workout metric: $key")
                }
            }
        }
        val sample = WorkoutSample(
            workoutId = workout.id,
            sequence = workout.sampleCount,
            timestampEpochMs = event.timestampEpochMs,
            offsetS = event.offsetS,
            sessionState = sampleState,
            powerW = metric("powerW"),
            distanceM = metric("distanceM"),
            resistanceLevel = resistanceLevel,
            elapsedS = metric("elapsedS"),
            caloriesKcal = metric("caloriesKcal"),
            strokesCount = metric("strokesCount"),
            cadenceSpm = cadenceSpm,
            paceSPer500M = metric("paceSPer500M"),
            speedKmh = metric("speedKmh"),
            unvalidatedMetricsJson = unvalidatedMetrics.takeIf { it.length() > 0 }?.toString(),
        )
        dao.save(next, sample)
        current = if (finished) null else next
        rowerState = if (finished) null else state ?: rowerState
        lastMonotonicMs = if (finished) null else event.monotonicMs
    }

    private suspend fun interrupt(event: Event.Disconnect) {
        val workout = current ?: return
        dao.save(
            workout.copy(
                status = "interrupted",
                endedAtEpochMs = event.timestampEpochMs,
                endedOffsetS = event.offsetS,
            ),
            null,
        )
        current = null
        rowerState = null
        lastMonotonicMs = null
    }

    private fun offsetAt(timestamp: Long): Int = TimeZone.getDefault().getOffset(timestamp) / 1000

    private fun round2(value: Double): Double = round(value * 100) / 100
}
