package io.radrower

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionControlTest {
    @Test fun stateMustBeRecentAndAvailable() {
        val state = RowerUiState(readings = mapOf("sessionState" to Reading(2, 1_000)))
        assertEquals(2, state.sessionState(6_000))
        assertNull(state.sessionState(6_001))
        assertNull(RowerUiState().sessionState(1_000))
        assertNull(RowerUiState(readings = mapOf("sessionState" to Reading(null, 1_000))).sessionState(1_000))
    }

    @Test fun commandsOnlyExistForValidStates() {
        for (state in listOf(null, 0, 4, 10)) {
            assertNull(commandSteps(state, stop = false))
            assertNull(commandSteps(state, stop = true))
        }
        assertNull(commandSteps(1, stop = true))
        assertEquals(RowerProtocol.start, requireNotNull(commandSteps(1, false)).single().messages)
        assertEquals(RowerProtocol.start, requireNotNull(commandSteps(3, false)).single().messages)
        assertEquals(RowerProtocol.pause, requireNotNull(commandSteps(2, false)).single().messages)
    }

    @Test fun stopSequencesPauseEndAndIdle() = runBlocking {
        for ((state, expected) in listOf(2 to listOf(3, 4, 1), 3 to listOf(4, 1))) {
            val sent = mutableListOf<CommandStep>()
            runCommandSteps(requireNotNull(commandSteps(state, true))) { sent += it }
            assertEquals(expected, sent.map { it.expectedState })
            assertEquals(false, sent.first { it.expectedState == 4 }.required)
            assertEquals(RowerProtocol.idle, sent.last().messages)
        }
    }

    @Test fun failedPausePreventsEndAndIdle() = runBlocking {
        val sent = mutableListOf<Int>()
        try {
            runCommandSteps(requireNotNull(commandSteps(2, true))) {
                sent += it.expectedState
                error("No confirmation")
            }
            fail("Expected the command failure")
        } catch (error: IllegalStateException) {
            assertEquals("No confirmation", error.message)
        }
        assertEquals(listOf(3), sent)
    }

    @Test fun formatsTimeAndUnavailableValues() {
        assertEquals("5:03", formatMetric("pace", 303))
        assertEquals("—", formatMetric("watts", null))
        assertEquals("? 0xff", formatMetric("resistance", "? 0xff"))
    }
}
