package com.danielealbano.androidremotecontrolmcp.ui.navigation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("SwipeBackTracker")
class SwipeBackTrackerTest {
    private val tracker = SwipeBackTracker(thresholdPx = 100f)

    @Test
    fun `a long enough rightward swipe goes back`() {
        tracker.start()
        assertTrue(tracker.drag(60f))
        assertTrue(tracker.drag(50f))
        assertTrue(tracker.shouldGoBack())
    }

    @Test
    fun `a short swipe or a leftward one does not`() {
        tracker.start()
        tracker.drag(40f)
        assertFalse(tracker.shouldGoBack())
        tracker.start()
        assertFalse(tracker.drag(-150f))
        assertFalse(tracker.shouldGoBack())
    }

    @Test
    fun `a swipe that turns back before release is judged by where it ends`() {
        tracker.start()
        tracker.drag(150f)
        tracker.drag(-80f)
        assertFalse(tracker.shouldGoBack())
    }

    @Test
    fun `each swipe starts from zero`() {
        tracker.start()
        tracker.drag(150f)
        tracker.start()
        tracker.drag(30f)
        assertFalse(tracker.shouldGoBack())
    }
}
