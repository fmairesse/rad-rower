package io.radrower

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SessionControlTest {
    @Test fun stateMustBeRecentAndAvailable() {
        val state = RowerUiState(readings = mapOf("sessionState" to Reading(2, 1_000)))
        assertEquals(SessionState.IN_PROGRESS, state.sessionState(6_000))
        assertNull(state.sessionState(6_001))
        assertNull(RowerUiState().sessionState(1_000))
        assertNull(RowerUiState(readings = mapOf("sessionState" to Reading(null, 1_000))).sessionState(1_000))
        assertNull(RowerUiState(readings = mapOf("sessionState" to Reading(10, 1_000))).sessionState(1_000))
    }

    @Test fun commandsOnlyExistForValidStates() {
        for (state in listOf(null, SessionState.FINISHED)) {
            assertNull(commandSteps(state, stop = false))
            assertNull(commandSteps(state, stop = true))
        }
        assertNull(commandSteps(SessionState.IDLE, stop = true))
        assertEquals(RowerProtocol.start, requireNotNull(commandSteps(SessionState.IDLE, false)).single().messages)
        assertEquals(RowerProtocol.start, requireNotNull(commandSteps(SessionState.PAUSED, false)).single().messages)
        assertEquals(RowerProtocol.pause, requireNotNull(commandSteps(SessionState.IN_PROGRESS, false)).single().messages)
    }

    @Test fun stopSequencesPauseEndAndIdle() =
        runBlocking {
            for ((state, expected) in listOf(
                SessionState.IN_PROGRESS to listOf(SessionState.PAUSED, SessionState.FINISHED, SessionState.IDLE),
                SessionState.PAUSED to listOf(SessionState.FINISHED, SessionState.IDLE),
            )) {
                val sent = mutableListOf<CommandStep>()
                runCommandSteps(requireNotNull(commandSteps(state, true))) { sent += it }
                assertEquals(expected, sent.map { it.expectedState })
                assertEquals(false, sent.first { it.expectedState == SessionState.FINISHED }.required)
                assertEquals(RowerProtocol.idle, sent.last().messages)
            }
        }

    @Test fun failedPausePreventsEndAndIdle() =
        runBlocking {
            val sent = mutableListOf<SessionState>()
            try {
                runCommandSteps(requireNotNull(commandSteps(SessionState.IN_PROGRESS, true))) {
                    sent += it.expectedState
                    error("No confirmation")
                }
                fail("Expected the command failure")
            } catch (error: IllegalStateException) {
                assertEquals("No confirmation", error.message)
            }
            assertEquals(listOf(SessionState.PAUSED), sent)
        }

    @Test fun formatsTimeAndUnavailableValues() {
        assertEquals("5:03", formatMetric("pace", 303))
        assertEquals("—", formatMetric("watts", null))
        assertEquals("? 0xff", formatMetric("resistance", "? 0xff"))
    }
}
