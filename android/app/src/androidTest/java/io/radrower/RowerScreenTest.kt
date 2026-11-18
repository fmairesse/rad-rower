package io.radrower

import android.os.SystemClock
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RowerScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun permissionIsRequestedFromScreen() {
        var requests = 0
        compose.setContent {
            RowerScreen(RowerUiState(), false, false, true,
                { requests++ }, {}, {}, {}, {})
        }
        compose.onNodeWithText("Bluetooth permission is required.").assertIsDisplayed()
        compose.onAllNodesWithText("Session").assertCountEquals(0)
        compose.onNodeWithText("Allow Bluetooth").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("Start").assertIsNotEnabled()
        compose.onNodeWithText("Stop").assertIsNotEnabled()
    }

    @Test fun activeSessionEnablesPauseAndStop() {
        var pauses = 0
        var stops = 0
        val now = SystemClock.elapsedRealtime()
        compose.setContent {
            RowerScreen(
                RowerUiState(ConnectionState.STREAMING,
                    mapOf("sessionState" to Reading(2, now), "watts" to Reading(300, now))),
                true, true, true, {}, {}, {}, { pauses++ }, { stops++ },
            )
        }
        compose.onNodeWithText("Connected to I_RW").assertIsDisplayed()
        compose.onNodeWithText("Session").assertIsDisplayed()
        compose.onNodeWithText("Pause").assertIsEnabled().performClick()
        compose.onNodeWithText("Stop").assertIsEnabled().performClick()
        compose.onNodeWithText("300 W").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, pauses)
            assertEquals(1, stops)
        }
    }

    @Test fun staleSessionDisablesCommands() {
        compose.setContent {
            RowerScreen(
                RowerUiState(ConnectionState.STREAMING,
                    mapOf("sessionState" to Reading(2, SystemClock.elapsedRealtime() - 6_000))),
                true, true, true, {}, {}, {}, {}, {},
            )
        }
        compose.onNodeWithText("State unavailable").assertIsDisplayed()
        compose.onNodeWithText("Start").assertIsNotEnabled()
        compose.onNodeWithText("Stop").assertIsNotEnabled()
    }
}
