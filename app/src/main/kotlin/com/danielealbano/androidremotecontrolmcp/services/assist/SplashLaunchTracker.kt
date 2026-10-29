package com.danielealbano.androidremotecontrolmcp.services.assist

/** How a foreground window change should be treated by [SplashLaunchTracker]. */
enum class ForegroundKind {
    /** A regular app that may show a splash ad when it comes to the foreground. */
    APP,

    /** Launcher / settings-like surfaces: they end the current window but never get one. */
    NO_SPLASH,

    /**
     * Overlays that sit on top of the current app (system UI, IME, this app's own overlay):
     * ignored entirely, so pulling the notification shade does not restart the window.
     */
    TRANSIENT,
}

/**
 * Tracks the short "launch window" after an app comes to the foreground, during which the
 * splash-ad skipper scans the screen. Pure state machine with an injected clock value so it is
 * unit-testable; NOT thread-safe — call it from the accessibility service's main thread only.
 */
class SplashLaunchTracker(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxClicks: Int = DEFAULT_MAX_CLICKS,
    private val minClickIntervalMs: Long = DEFAULT_MIN_CLICK_INTERVAL_MS,
    private val scanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS,
) {
    private var foregroundPackage: String? = null
    private var windowPackage: String? = null
    private var windowStartedAt = 0L
    private var clicks = 0
    private var lastClickAt: Long? = null
    private var lastScanAt: Long? = null

    /**
     * Records a foreground window change. Returns true when a NEW launch window opened for
     * [packageName] (i.e. the app just came to the foreground).
     */
    fun onForeground(
        packageName: String,
        kind: ForegroundKind,
        now: Long,
    ): Boolean {
        if (kind == ForegroundKind.TRANSIENT || packageName == foregroundPackage) return false
        foregroundPackage = packageName
        clicks = 0
        lastClickAt = null
        lastScanAt = null
        val opensWindow = kind == ForegroundKind.APP
        windowPackage = packageName.takeIf { opensWindow }
        windowStartedAt = now
        return opensWindow
    }

    /** The package whose launch window is still open at [now], or null. */
    fun activePackage(now: Long): String? = windowPackage?.takeIf { now - windowStartedAt <= windowMs }

    /**
     * Returns true (and records the scan) when an event from [packageName] at [now] should trigger
     * a screen scan: the window is open, the click budget is not spent, the previous click has had
     * time to take effect, and scans are rate-limited to one per [scanIntervalMs].
     */
    fun tryBeginScan(
        packageName: String,
        now: Long,
    ): Boolean {
        val due =
            activePackage(now) == packageName &&
                clicks < maxClicks &&
                lastClickAt.let { it == null || now - it >= minClickIntervalMs } &&
                lastScanAt.let { it == null || now - it >= scanIntervalMs }
        if (due) lastScanAt = now
        return due
    }

    /** Skip presses already made in the current window. */
    val clicksInWindow: Int
        get() = clicks

    /** Records that a skip control was clicked at [now]. */
    fun onSkipClicked(now: Long) {
        clicks++
        lastClickAt = now
    }

    companion object {
        /** Splash ads run 3–5 s; slow cold starts push the ad itself a few seconds later. */
        const val DEFAULT_WINDOW_MS = 8_000L

        /** Some SDKs show a second ad after the first skip; never click more than this. */
        const val DEFAULT_MAX_CLICKS = 2
        const val DEFAULT_MIN_CLICK_INTERVAL_MS = 800L
        const val DEFAULT_SCAN_INTERVAL_MS = 150L
    }
}
