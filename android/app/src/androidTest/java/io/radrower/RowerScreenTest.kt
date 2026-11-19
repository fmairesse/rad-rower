package io.radrower

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RowerScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun permissionIsRequestedFromScreen() {
        var requests = 0
        compose.setContent {
            RowerScreen(
                RowerUiState(),
                permissionGranted = false,
                bluetoothEnabled = false,
                bluetoothAvailable = true,
                { requests++ },
                {},
                {},
                {},
                {},
            )
        }
        compose.onNodeWithText("Bluetooth permission is required.").assertIsDisplayed()
        compose.onAllNodesWithText("Session").assertCountEquals(0)
        compose.onNodeWithText("Allow Bluetooth").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun activeSessionEnablesPauseAndStop() {
        var pauses = 0
        var stops = 0
        val now = SystemClock.elapsedRealtime()
        compose.setContent {
            RowerScreen(
                RowerUiState(
                    ConnectionState.STREAMING,
                    mapOf("sessionState" to Reading(2, now), "powerW" to Reading(300, now)),
                ),
                permissionGranted = true,
                bluetoothEnabled = true,
                bluetoothAvailable = true,
                onPermission = {},
                onEnableBluetooth = {},
                onRetry = {},
                onToggle = { pauses++ },
                onStop = { stops++ },
            )
        }
        compose.onAllNodesWithText("Connected to I_RW").assertCountEquals(0)
        compose.onNodeWithText("Session").assertIsDisplayed()
        compose.onNodeWithText("Pause").assertIsEnabled().performClick()
        compose.onNodeWithText("Stop").assertIsEnabled().performClick()
        compose.onNodeWithTag("dashboardGrid").performScrollToNode(hasText("300 W"))
        compose.onNodeWithText("300 W").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, pauses)
            assertEquals(1, stops)
        }
    }

    @Test fun metricsGridAdaptsToAvailableWidth() {
        var width by mutableStateOf(320.dp)
        val now = SystemClock.elapsedRealtime()
        compose.setContent {
            Box(Modifier.requiredWidth(width)) {
                RowerScreen(
                    RowerUiState(
                        ConnectionState.STREAMING,
                        mapOf("sessionState" to Reading(2, now)),
                    ),
                    permissionGranted = true,
                    bluetoothEnabled = true,
                    bluetoothAvailable = true,
                    onPermission = {},
                    onEnableBluetooth = {},
                    onRetry = {},
                    onToggle = {},
                    onStop = {},
                )
            }
        }

        fun bounds(label: String) = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot

        compose.onNodeWithTag("dashboardGrid").performScrollToNode(hasText("Pace"))
        val power = bounds("Power")
        val elapsedS = bounds("Elapsed time")
        val pace = bounds("Pace")
        assertEquals(power.top, elapsedS.top, 0.5f)
        assertTrue(elapsedS.left > power.left)
        assertTrue(pace.top > power.top)

        compose.runOnIdle { width = 700.dp }
        compose.onNodeWithTag("dashboardGrid").performScrollToNode(hasText("Cadence"))
        val widePower = bounds("Power")
        val widePace = bounds("Pace")
        val wideCadence = bounds("Cadence")
        assertEquals(widePower.top, widePace.top, 0.5f)
        assertTrue(widePace.left > widePower.left)
        assertTrue(wideCadence.top > widePower.top)
    }

    @Test fun connectionCardTracksSessionAndConnectionState() {
        val now = SystemClock.elapsedRealtime()
        var state by mutableStateOf(
            RowerUiState(
                connection = ConnectionState.STREAMING,
                readings = mapOf("sessionState" to Reading(1, now)),
            ),
        )
        compose.setContent {
            RowerScreen(
                state,
                permissionGranted = true,
                bluetoothEnabled = true,
                bluetoothAvailable = true,
                onPermission = {},
                onEnableBluetooth = {},
                onRetry = {},
                onToggle = {},
                onStop = {},
            )
        }

        compose.onNodeWithText("Connected to I_RW").assertIsDisplayed()
        compose.runOnIdle {
            state = state.copy(
                readings = mapOf("sessionState" to Reading(2, SystemClock.elapsedRealtime())),
                message = "Session command failed.",
            )
        }
        compose.onAllNodesWithText("Connected to I_RW").assertCountEquals(0)
        compose.onNodeWithText("Session command failed.").assertIsDisplayed()

        compose.runOnIdle {
            state = state.copy(readings = mapOf("sessionState" to Reading(3, SystemClock.elapsedRealtime())))
        }
        compose.onNodeWithText("Connected to I_RW").assertIsDisplayed()
        compose.onAllNodesWithText("Session command failed.").assertCountEquals(1)

        compose.runOnIdle {
            state = state.copy(
                connection = ConnectionState.DISCONNECTED,
                readings = mapOf("sessionState" to Reading(2, SystemClock.elapsedRealtime())),
            )
        }
        compose.onNodeWithText("Disconnected").assertIsDisplayed()
        compose.onNodeWithText("Retry connection").assertIsDisplayed()
    }

    @Test fun staleSessionDisablesCommands() {
        compose.setContent {
            RowerScreen(
                RowerUiState(
                    ConnectionState.STREAMING,
                    mapOf("sessionState" to Reading(2, SystemClock.elapsedRealtime() - 6_000)),
                ),
                permissionGranted = true,
                bluetoothEnabled = true,
                bluetoothAvailable = true,
                onPermission = {},
                onEnableBluetooth = {},
                onRetry = {},
                onToggle = {},
                onStop = {},
            )
        }
        compose.onNodeWithText("Connected to I_RW").assertIsDisplayed()
        compose.onNodeWithText("State unavailable").assertIsDisplayed()
        compose.onNodeWithText("Start").assertIsNotEnabled()
        compose.onNodeWithText("Stop").assertIsNotEnabled()
    }
}
