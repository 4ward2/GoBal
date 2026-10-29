package com.danielealbano.androidremotecontrolmcp.services.assist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import com.danielealbano.androidremotecontrolmcp.utils.PlatformCompat

/**
 * Presses the "跳过/Skip" control of launch (splash) ads, like 李跳跳.
 *
 * For a few seconds after an app comes to the foreground ([SplashLaunchTracker]) every window
 * event from that app triggers a bounded scan of its windows for a node accepted by
 * [SplashSkipRules]. The first press uses the accessibility click action (it cannot be caught by a
 * transparent trap view layered over the button). Some views accept that action without reacting
 * — WebView-rendered splashes and custom views that only handle touch events — so when the skip
 * control is still on screen at the next scan, it is tapped with a real gesture at its center.
 * Never through a clickable ancestor, which on ad layouts is usually the ad itself.
 *
 * Main-thread only (called from [AccessibilityService.onAccessibilityEvent]).
 */
class SplashAdSkipper(
    private val service: AccessibilityService,
    private val tracker: SplashLaunchTracker = SplashLaunchTracker(),
) {
    private val transientPackages: Set<String> = TRANSIENT_SYSTEM_PACKAGES + service.packageName + imePackages()
    private val noSplashPackages: Set<String> = NO_SPLASH_SYSTEM_PACKAGES + homePackages()

    /** Handles an accessibility event; a no-op outside an app's launch window. */
    fun onEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val now = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            tracker.onForeground(packageName, classify(packageName), now)
        }
        if (tracker.tryBeginScan(packageName, now)) scanAndPress(packageName, now)
    }

    private fun scanAndPress(
        packageName: String,
        now: Long,
    ) {
        val target = findSkipControl(packageName) ?: return
        val useGesture = tracker.clicksInWindow > 0 || !target.isClickable
        val pressed = if (useGesture) tap(target) else target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (pressed) {
            tracker.onSkipClicked(now)
            Log.i(TAG, "Pressed splash-ad skip in $packageName (${describe(target)}, gesture=$useGesture)")
        }
    }

    private fun classify(packageName: String): ForegroundKind =
        when (packageName) {
            in transientPackages -> ForegroundKind.TRANSIENT
            in noSplashPackages -> ForegroundKind.NO_SPLASH
            else -> ForegroundKind.APP
        }

    private fun findSkipControl(packageName: String): AccessibilityNodeInfo? {
        val metrics = service.resources.displayMetrics
        val roots =
            service.windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .filter { it.packageName?.toString() == packageName }
        for (root in roots) {
            findInTree(root, metrics.widthPixels, metrics.heightPixels)?.let { return it }
        }
        return null
    }

    private fun findInTree(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val bounds = Rect()
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES_PER_SCAN) {
            val node = queue.removeFirst()
            visited++
            if (!node.isVisibleToUser) continue
            node.getBoundsInScreen(bounds)
            val facts =
                SplashNodeFacts(
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    viewIdResourceName = node.viewIdResourceName,
                    width = bounds.width(),
                    height = bounds.height(),
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                )
            if (SplashSkipRules.isSkipControl(facts)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun tap(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture =
            GestureDescription
                .Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
                .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun describe(node: AccessibilityNodeInfo): String =
        node.text?.toString() ?: node.contentDescription?.toString() ?: node.viewIdResourceName ?: "?"

    private fun homePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return PlatformCompat
            .activitiesFor(service.packageManager, intent)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    private fun imePackages(): Set<String> {
        val imm = service.getSystemService(InputMethodManager::class.java) ?: return emptySet()
        return imm.enabledInputMethodList.map { it.packageName }.toSet()
    }

    companion object {
        private const val TAG = "MCP:SplashAdSkipper"
        private const val MAX_NODES_PER_SCAN = 600
        private const val TAP_DURATION_MS = 60L

        /** Overlays drawn over the current app; they must not restart its launch window. */
        private val TRANSIENT_SYSTEM_PACKAGES =
            setOf(
                "android",
                "com.android.systemui",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
            )

        /** System surfaces that never show splash ads. */
        private val NO_SPLASH_SYSTEM_PACKAGES = setOf("com.android.settings")
    }
}
