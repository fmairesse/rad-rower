package io.radrower

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutHistoryTest {
    @Test fun formatsDurations() {
        assertEquals("—", formatWorkoutDuration(null))
        assertEquals("0:00", formatWorkoutDuration(0.0))
        assertEquals("5:03", formatWorkoutDuration(303.0))
        assertEquals("1:02:03", formatWorkoutDuration(3723.0))
        assertEquals("27:00:00", formatWorkoutDuration(97200.0))
    }
}
