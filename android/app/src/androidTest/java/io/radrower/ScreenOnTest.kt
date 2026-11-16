package io.radrower

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ScreenOnTest {
    @get:Rule val compose = createComposeRule()

    @Test fun screenFlagFollowsConfirmedSessionAndConnection() {
        lateinit var activity: ComponentActivity
        compose.setContent { activity = LocalContext.current as ComponentActivity }
        compose.waitForIdle()

        runBlocking {
            withContext(Dispatchers.Main) {
                val states = MutableStateFlow(RowerUiState())
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    keepScreenOnWhileActive(activity.window, states)
                }
                suspend fun check(state: RowerUiState, expected: Boolean) {
                    states.value = state
                    yield()
                    assertEquals(
                        expected,
                        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0,
                    )
                }
                fun state(connection: ConnectionState, session: Int?) = RowerUiState(
                    connection = connection,
                    readings = if (session == null) emptyMap() else mapOf("sessionState" to Reading(session, 0)),
                )
                try {
                    check(state(ConnectionState.DISCONNECTED, null), false)
                    check(state(ConnectionState.STREAMING, null), false)
                    check(state(ConnectionState.STREAMING, 1), false)
                    check(state(ConnectionState.STREAMING, 2), true)
                    check(state(ConnectionState.STREAMING, 3), false)
                    check(state(ConnectionState.STREAMING, 2), true)
                    check(state(ConnectionState.STREAMING, 4), false)
                    check(state(ConnectionState.STREAMING, 2), true)
                    check(state(ConnectionState.DISCONNECTED, 2), false)
                    check(state(ConnectionState.SCANNING, 2), false)
                    check(state(ConnectionState.INITIALIZING, 2), false)
                    check(state(ConnectionState.STREAMING, 10), false)
                } finally {
                    collector.cancelAndJoin()
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }
}
