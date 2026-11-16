package io.radrower

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.text.DateFormat
import java.util.Date

class WorkoutHistoryScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun workout(id: String, timestamp: Long = 1_700_000_000_000) = Workout(
        id = id, startedAt = timestamp, startedOffsetSeconds = 0,
        endedAt = timestamp + 3_723_000, endedOffsetSeconds = 0,
        lastSampleAt = timestamp, lastSampleOffsetSeconds = 0,
        status = "completed", partial = false,
        elapsed = 3723.0, distance = 1234.0, calories = 42.0,
    )

    @Test fun historyShowsDetailsAndUnavailableValues() {
        compose.setContent {
            WorkoutHistoryScreen(
                listOf(workout("one"), workout("two", 1_600_000_000_000).copy(
                    elapsed = null, distance = null, calories = null,
                    status = "interrupted", partial = true,
                )), null, {},
            )
        }
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(1_700_000_000_000))
        compose.onNodeWithText(date).assertExists()
        compose.onNodeWithText("Duration: 1:02:03").assertIsDisplayed()
        compose.onNodeWithText("Distance: ${formatMetric("distance", 1234.0)} m").assertIsDisplayed()
        compose.onNodeWithText("Calories: 42 kcal").assertIsDisplayed()
        compose.onNodeWithText("Interrupted · partial").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Duration: —").assertIsDisplayed()
        compose.onNodeWithText("Distance: —").assertIsDisplayed()
        compose.onNodeWithText("Calories: —").assertIsDisplayed()
    }

    @Test fun historyContainsAllWorkoutsNotOnlyTheFirstFive() {
        val workouts = (0..9).map { workout("workout-$it", 1_700_000_000_000L - it * 86_400_000L) }
        val oldest = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(workouts.last().startedAt))
        compose.setContent { WorkoutHistoryScreen(workouts, null, {}) }
        compose.onNodeWithTag("workoutHistory").performScrollToNode(hasText(oldest))
        compose.onNodeWithText(oldest).assertIsDisplayed()
    }

    @Test fun exportsOnlyEligibleWorkoutsWhenRequested() {
        var exported: Workout? = null
        compose.setContent {
            WorkoutHistoryScreen(
                listOf(
                    workout("complete"),
                    workout("partial").copy(partial = true),
                    workout("interrupted").copy(status = "interrupted"),
                ),
                null,
                {},
                onExport = { exported = it },
            )
        }

        compose.onNodeWithText("Export to Health Connect").performClick()
        compose.runOnIdle { assertEquals("complete", exported?.id) }
        compose.onAllNodesWithText("Export to Health Connect").assertCountEquals(1)
    }

    @Test fun emptyAndErrorStatesAreDistinct() {
        var error by mutableStateOf<String?>(null)
        compose.setContent { WorkoutHistoryScreen(emptyList(), error, {}) }
        compose.onNodeWithText("No workouts saved yet.").assertIsDisplayed()
        compose.runOnIdle { error = "Unable to read workouts." }
        compose.onNodeWithText("Unable to read workouts.").assertIsDisplayed()
        compose.onNodeWithText("No workouts saved yet.").assertDoesNotExist()
    }

    @Test fun navigationAndSystemBackReturnToDashboard() {
        compose.setContent {
            RadrowerScreens(RowerUiState(), true, true, true, {}, {}, {}, {}, {},
                listOf(workout("one")), null)
        }
        compose.onNodeWithText("View workout history").performScrollTo().performClick()
        compose.onNodeWithText("Workout history").assertIsDisplayed()
        compose.onNodeWithText("Back to dashboard").performClick()
        compose.onNodeWithText("RADROWER").assertIsDisplayed()
        compose.onNodeWithText("View workout history").performScrollTo().performClick()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
        }
        compose.onNodeWithText("RADROWER").assertIsDisplayed()
    }
}
