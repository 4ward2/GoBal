package com.danielealbano.androidremotecontrolmcp.services.assist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("SplashLaunchTracker")
class SplashLaunchTrackerTest {
    private val tracker =
        SplashLaunchTracker(windowMs = 8_000L, maxClicks = 2, minClickIntervalMs = 800L, scanIntervalMs = 150L)

    @Test
    fun `app coming to the foreground opens a launch window`() {
        assertTrue(tracker.onForeground(APP, ForegroundKind.APP, now = 0L))
        assertEquals(APP, tracker.activePackage(now = 1_000L))
        assertTrue(tracker.tryBeginScan(APP, now = 1_000L))
    }

    @Test
    fun `window closes after windowMs`() {
        tracker.onForeground(APP, ForegroundKind.APP, now = 0L)
        assertNull(tracker.activePackage(now = 8_001L))
        assertFalse(tracker.tryBeginScan(APP, now = 8_001L))
    }

    @Test
    fun `events from another package never scan`() {
        tracker.onForeground(APP, ForegroundKind.APP, now = 0L)
        assertFalse(tracker.tryBeginScan("com.other", now = 100L))
    }

    @Test
    fun `scans are rate limited`() {
        tracker.onForeground(APP, ForegroundKind.APP, now = 0L)
        assertTrue(tracker.tryBeginScan(APP, now = 0L))
        assertFalse(tracker.tryBeginScan(APP, now = 149L))
        assertTrue(tracker.tryBeginScan(APP, now = 150L))
    }

    @Test
    fun `click budget and click interval are enforced`() {
        tracker.onForeground(APP, ForegroundKind.APP, now = 0L)
        tracker.onSkipClicked(now = 100L)
        assertFalse(tracker.tryBeginScan(APP, now = 899L), "previous click needs time to take effect")
        assertTrue(tracker.tryBeginScan(APP, now = 900L))
        tracker.onSkipClicked(now = 900L)
        assertFalse(tracker.tryBeginScan(APP, now = 3_000L), "budget of 2 clicks spent")
    }

    @Test
    fun `clicksInWindow counts presses and resets with a new window`() {
        tracker.onForeground(APP, ForegroundKind.APP, now = 0L)
        tracker.onSkipClicked(now = 100L)
        assertEquals(1, tracker.clicksInWindow)
        tracker.onForeground(LAUNCHER, ForegroundKind.NO_SPLASH, now = 5_000L)
        tracker.onForeground(APP, ForegroundKind.APP, now = 6_000L)
        assertEquals(0, tracker.clicksInWindow)
    }

    @Test
    fun `transient overlays do not restart the window`() {
        tracker.onForeground(APP, ForegroundKind.APP, now = 0L)
        tracker.onSkipClicked(now = 100L)
        tracker.onSkipClicked(now = 1_000L)
        assertFalse(tracker.onForeground("com.android.systemui", ForegroundKind.TRANSIENT, now = 2_000L))
        assertFalse(tracker.onForeground(APP, ForegroundKind.APP, now = 2_500L), "same app, still foreground")
        assertFalse(tracker.tryBeginScan(APP, now = 3_000L))
    }

    @Test
    fun `returning from the launcher opens a fresh window`() {
        tracker.onForeground(APP, ForegroundKind.APP, now = 0L)
        tracker.onSkipClicked(now = 100L)
        tracker.onSkipClicked(now = 1_000L)
        assertFalse(tracker.onForeground(LAUNCHER, ForegroundKind.NO_SPLASH, now = 20_000L))
        assertNull(tracker.activePackage(now = 20_000L))
        assertTrue(tracker.onForeground(APP, ForegroundKind.APP, now = 30_000L))
        assertTrue(tracker.tryBeginScan(APP, now = 30_000L))
    }

    @Test
    fun `no-splash surfaces never get a window`() {
        assertFalse(tracker.onForeground(LAUNCHER, ForegroundKind.NO_SPLASH, now = 0L))
        assertFalse(tracker.tryBeginScan(LAUNCHER, now = 0L))
    }

    companion object {
        private const val APP = "com.sankuai.meituan"
        private const val LAUNCHER = "org.lineageos.trebuchet"
    }
}
