package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.assist.AssistController
import com.danielealbano.androidremotecontrolmcp.services.memory.OcrEngine
import com.danielealbano.androidremotecontrolmcp.services.skill.ManualRecording
import com.danielealbano.androidremotecontrolmcp.services.skill.RecorderState
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillEventCapture
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRecorder
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRecordingUi
import com.danielealbano.androidremotecontrolmcp.utils.ApiLevel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

@Suppress("TooManyFunctions")
class McpAccessibilityService : AccessibilityService() {
    // AccessibilityNodeCache is in the same package — no import needed
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NodeCacheEntryPoint {
        fun nodeCache(): AccessibilityNodeCache

        fun assistSettingsRepository(): AssistSettingsRepository

        fun skillRecorder(): SkillRecorder

        fun manualRecording(): ManualRecording

        fun ocrEngine(): OcrEngine
    }

    private var serviceScope: CoroutineScope? = null

    private var nodeCache: AccessibilityNodeCache? = null

    private var cacheInvalidationDebouncer: CacheInvalidationDebouncer? = null

    private var assistController: AssistController? = null

    private var skillCapture: SkillEventCapture? = null

    private var skillRecordingUi: SkillRecordingUi? = null

    /** Whether the recording-only event types are currently subscribed. */
    private var subscribedForRecording = false

    @Volatile
    private var currentPackageName: String? = null

    @Volatile
    private var currentActivityName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        serviceScope = scope
        nodeCache = resolveNodeCache()
        cacheInvalidationDebouncer =
            CacheInvalidationDebouncer(
                scope = scope,
                debounceMillis = CACHE_INVALIDATION_DEBOUNCE_MS,
                onSettled = { invalidateCache(nodeCache) },
            )

        configureServiceInfo()

        assistController = resolveAssistSettingsRepository()?.let { AssistController(this, scope, it) }
        assistController?.start()

        resolveSkillRecorder()?.let { recorder -> startSkillRecording(recorder, scope) }

        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                event.packageName?.toString()?.let { packageName ->
                    currentPackageName = packageName
                }
                event.className?.toString()?.let { className ->
                    currentActivityName = className
                }
                Log.d(
                    TAG,
                    "Window state changed: package=$currentPackageName, " +
                        "activity=$currentActivityName",
                )
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Soft-keyboard show/hide and other window add/remove/bounds changes arrive here.
                Log.d(TAG, "Windows changed (e.g. soft-keyboard show/hide)")
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                Log.d(TAG, "Window content changed: package=${event.packageName}")
            }

            else -> {
                // Ignored event types
            }
        }

        // A structural window change (keyboard show/hide, rotation, activity/dialog transition)
        // shifts element bounds. Because cached node ids are derived from bounds, and which
        // elements are present/actionable also changes, the cached id->node entries become stale.
        // Schedule a debounced invalidation so the cache is dropped once — and only once — AFTER
        // the transition settles, so the next node lookup resolves against the live tree.
        scheduleCacheInvalidationIfNeeded(event.eventType, cacheInvalidationDebouncer)

        skillCapture?.onEvent(event)
        // The assist features keep seeing only the event types they were written for.
        if (event.eventType and SkillEventCapture.RECORDING_EVENT_TYPES == 0) assistController?.onEvent(event)
    }

    /** Shows the recording bar and subscribes to clicks / text changes only while a skill is recorded. */
    private fun startSkillRecording(
        recorder: SkillRecorder,
        scope: CoroutineScope,
    ) {
        val capture = SkillEventCapture(this, recorder)
        val entry = EntryPointAccessors.fromApplication(applicationContext, NodeCacheEntryPoint::class.java)
        val ui = SkillRecordingUi(this, scope, entry.manualRecording(), entry.ocrEngine()) { takeScreenshotBitmap() }
        skillCapture = capture
        skillRecordingUi = ui
        scope.launch {
            recorder.state.collect { state ->
                val recording = state is RecorderState.Recording
                if (recording != subscribedForRecording) {
                    if (recording) capture.refreshIgnoredPackages()
                    subscribedForRecording = recording
                    configureServiceInfo()
                }
                ui.render(state)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.i(TAG, "Accessibility service destroying")

        assistController?.stop()
        assistController = null

        skillRecordingUi?.dispose()
        skillRecordingUi = null
        skillCapture = null

        // Stop any pending debounced invalidation before tearing down the scope it runs on.
        cacheInvalidationDebouncer?.cancel()
        cacheInvalidationDebouncer = null

        // Flush the node cache — all AccessibilityNodeInfo references become invalid.
        nodeCache?.clear()
        nodeCache = null

        serviceScope?.cancel()
        serviceScope = null
        currentPackageName = null
        currentActivityName = null
        inputMethodInstance = null
        instance = null

        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory condition reported")
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val levelName =
            when (level) {
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
                ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
                else -> "UNKNOWN($level)"
            }
        Log.w(TAG, "Trim memory: level=$levelName")
    }

    /**
     * Returns the root [AccessibilityNodeInfo] of the currently active window,
     * or null if no window is available or it belongs to this app (see [getAccessibilityWindows]).
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        val root =
            if (TargetDisplay.isDefault) {
                rootInActiveWindow
            } else {
                getAccessibilityWindows()
                    .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    ?.root
            }
        return root?.takeIf { it.packageName?.toString() != packageName }
    }

    /**
     * Returns the target display's windows for the MCP tools, via [AccessibilityService.getWindows]
     * (or `getWindowsOnAllDisplays` for a shadow display).
     * Requires [AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS].
     *
     * This app's own windows are left out: its settings hold the personal vault and its approval
     * card must only ever be answered by the user, so the agent can neither read nor act on them.
     *
     * @return List of [AccessibilityWindowInfo], or empty list if unavailable.
     */
    fun getAccessibilityWindows(): List<AccessibilityWindowInfo> =
        targetDisplayWindows().filterNot { it.root?.packageName?.toString() == packageName }

    /**
     * True while one of this app's own windows is active or focused on the target display
     * (an approval card, the vault settings). Tool gestures are refused then.
     */
    fun isOwnWindowActive(): Boolean =
        targetDisplayWindows().any {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                (it.isActive || it.isFocused) &&
                it.root?.packageName?.toString() == packageName
        }

    private fun targetDisplayWindows(): List<AccessibilityWindowInfo> =
        try {
            val target = TargetDisplay.id
            if (target == Display.DEFAULT_DISPLAY || !ApiLevel.atLeast(Build.VERSION_CODES.R)) {
                windows ?: emptyList()
            } else {
                windowsOnAllDisplays.get(target) ?: emptyList()
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "getWindows() failed: ${e.message}")
            emptyList()
        }

    /**
     * Returns the package name of the currently focused application,
     * or null if unknown.
     */
    fun getCurrentPackageName(): String? = currentPackageName

    /**
     * Returns the class name (activity name) of the currently focused window,
     * or null if unknown.
     */
    fun getCurrentActivityName(): String? = currentActivityName

    /**
     * Returns true if the service is connected and ready to process requests.
     * Does NOT check for an active window — multi-window support handles
     * window availability at tree-parsing time.
     */
    fun isReady(): Boolean = instance != null

    /**
     * Returns the [CoroutineScope] for this service, or null if not connected.
     */
    fun getServiceScope(): CoroutineScope? = serviceScope

    /**
     * Returns the current screen dimensions, density, and orientation.
     *
     * @return [ScreenInfo] with width, height, densityDpi, and orientation.
     */
    fun getScreenInfo(): ScreenInfo {
        val displayContext = targetDisplayContext()
        val shadowMode = shadowDisplayMode(displayContext)
        val width: Int
        val height: Int
        if (shadowMode != null) {
            // A shadow display has a single mode equal to its virtual surface size.
            width = shadowMode.physicalWidth
            height = shadowMode.physicalHeight
        } else {
            val size = physicalScreenSize()
            width = size.x
            height = size.y
        }

        val displayMetrics = displayContext.resources.displayMetrics
        val densityDpi = displayMetrics.densityDpi

        val orientation =
            when (displayContext.resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> ScreenInfo.ORIENTATION_LANDSCAPE
                else -> ScreenInfo.ORIENTATION_PORTRAIT
            }

        return ScreenInfo(
            width = width,
            height = height,
            densityDpi = densityDpi,
            orientation = orientation,
        )
    }

    /**
     * Context bound to [TargetDisplay]; the service's own context when the target is the
     * physical screen or the shadow display has gone away.
     */
    private fun targetDisplayContext(): Context {
        val display =
            if (TargetDisplay.isDefault) {
                null
            } else {
                getSystemService(DisplayManager::class.java)?.getDisplay(TargetDisplay.id)
            }
        return display?.let { createDisplayContext(it) } ?: this
    }

    /** The display mode of the shadow display, or null when the target is the phone's own screen. */
    private fun shadowDisplayMode(displayContext: Context): Display.Mode? =
        if (displayContext === this || !ApiLevel.atLeast(Build.VERSION_CODES.R)) null else displayContext.display.mode

    private fun physicalScreenSize(): Point {
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (ApiLevel.atLeast(Build.VERSION_CODES.R)) {
            manager.currentWindowMetrics.bounds.let { Point(it.width(), it.height()) }
        } else {
            @Suppress("DEPRECATION")
            Point().also { manager.defaultDisplay.getRealSize(it) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateInputMethod(): InputMethod {
        val method = McpInputMethod(this)
        inputMethodInstance = method
        return method
    }

    private fun configureServiceInfo() {
        serviceInfo =
            serviceInfo?.apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    (if (subscribedForRecording) SkillEventCapture.RECORDING_EVENT_TYPES else 0)
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
                notificationTimeout = NOTIFICATION_TIMEOUT_MS
            }
        if (serviceInfo == null) {
            Log.w(TAG, "serviceInfo is null, cannot configure accessibility service settings")
        }
    }

    /**
     * Resolves the singleton [AccessibilityNodeCache] via Hilt's application entry point.
     * Returns null (and logs) if Hilt is not initialized, in which case cache invalidation
     * becomes a no-op rather than crashing the service.
     */
    private fun resolveNodeCache(): AccessibilityNodeCache? =
        try {
            EntryPointAccessors
                .fromApplication(applicationContext, NodeCacheEntryPoint::class.java)
                .nodeCache()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not resolve node cache", e)
            null
        }

    /**
     * Resolves the singleton [AssistSettingsRepository] for the assistive features. Returns null (and
     * logs) if Hilt is not initialized; the assistive features are then simply not started.
     */
    private fun resolveAssistSettingsRepository(): AssistSettingsRepository? =
        try {
            EntryPointAccessors
                .fromApplication(applicationContext, NodeCacheEntryPoint::class.java)
                .assistSettingsRepository()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not resolve settings repository", e)
            null
        }

    /** Resolves the skill recorder; null (and logged) if Hilt is not initialized: recording is then unavailable. */
    private fun resolveSkillRecorder(): SkillRecorder? =
        try {
            EntryPointAccessors
                .fromApplication(applicationContext, NodeCacheEntryPoint::class.java)
                .skillRecorder()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not resolve skill recorder", e)
            null
        }

    /**
     * Takes a screenshot using AccessibilityService.takeScreenshot() API.
     * Does NOT require user consent.
     *
     * @param timeoutMs Maximum time to wait for screenshot capture.
     * @return Bitmap of the screenshot, or null if capture failed or timed out.
     */
    suspend fun takeScreenshotBitmap(timeoutMs: Long = SCREENSHOT_TIMEOUT_MS): Bitmap? =
        if (ApiLevel.atLeast(Build.VERSION_CODES.R)) captureScreen(timeoutMs) else null

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreen(timeoutMs: Long): Bitmap? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val executor = Executor { it.run() }
                val callback =
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bitmap =
                                Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace,
                                )
                            screenshot.hardwareBuffer.close()
                            if (continuation.isActive) {
                                continuation.resume(bitmap)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot failed with error code: $errorCode")
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }

                takeScreenshot(TargetDisplay.id, executor, callback)
            }
        }

    /**
     * Whether this phone lets an accessibility service take screenshots: Android 11 and later.
     * Reading the screen and acting on it do not depend on this.
     */
    fun canTakeScreenshot(): Boolean = ApiLevel.atLeast(Build.VERSION_CODES.R)

    /**
     * Drops the framework's accessibility node cache for this service via [clearCache] (public
     * since API 33; older phones have no such call and keep their cache). See
     * [AccessibilityServiceProvider.clearFrameworkNodeCache] for
     * why this is needed to defeat stale WebView reads after JavaScript DOM changes.
     *
     * This is distinct from [invalidateCache], which flushes our own id→node [nodeCache]; this
     * clears the framework-side cache that backs [rootInActiveWindow]/[getWindows] traversal.
     */
    fun clearFrameworkNodeCache() {
        if (ApiLevel.atLeast(Build.VERSION_CODES.TIRAMISU)) clearCache()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    class McpInputMethod(
        service: AccessibilityService,
    ) : InputMethod(service)

    companion object {
        private const val TAG = "MCP:AccessibilityService"
        private const val NOTIFICATION_TIMEOUT_MS = 100L
        private const val SCREENSHOT_TIMEOUT_MS = 5000L

        /**
         * Length of the QUIET GAP (no further window-structure events) that must elapse before the
         * node cache is invalidated. The debounce timer resets on every event during a transition,
         * so this value is the silence required *after the last event*, not the total transition
         * duration. 250ms is an empirically chosen heuristic — long enough that a settling
         * keyboard/rotation has stopped emitting events, short enough to keep the post-transition
         * cache fresh quickly. Single tunable constant; validate/adjust against real-device traces.
         */
        private const val CACHE_INVALIDATION_DEBOUNCE_MS = 250L

        /**
         * Singleton instance of the accessibility service.
         * Set when the service connects, cleared when it is destroyed.
         * Access from other components to interact with the accessibility tree.
         */
        @Volatile
        var instance: McpAccessibilityService? = null
            private set

        @Volatile
        var inputMethodInstance: McpInputMethod? = null
            private set
    }
}

/**
 * Returns true if [eventType] is a structural window change after which cached node bounds may be
 * stale and the node cache should be invalidated: [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED]
 * (rotation, activity/dialog transition) and [AccessibilityEvent.TYPE_WINDOWS_CHANGED]
 * (soft-keyboard show/hide, window add/remove).
 *
 * Deliberately EXCLUDES [AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED], which fires far too
 * frequently (e.g. live text, progress bars) to drive cache invalidation without thrashing.
 *
 * Top-level and `internal` so the decision can be unit-tested without instantiating the service.
 */
internal fun triggersCacheInvalidation(eventType: Int): Boolean =
    eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

/**
 * Schedules a debounced cache invalidation iff [eventType] is a structural window change (see
 * [triggersCacheInvalidation]). No-op when [debouncer] is null (service not fully connected).
 *
 * Top-level and `internal` so the event-to-schedule wiring can be unit-tested without
 * instantiating the service.
 */
internal fun scheduleCacheInvalidationIfNeeded(
    eventType: Int,
    debouncer: CacheInvalidationDebouncer?,
) {
    if (triggersCacheInvalidation(eventType)) {
        debouncer?.schedule()
    }
}

/**
 * Clears [cache] if present. The whole-cache drop is the invalidation applied after a settled
 * window transition; a null [cache] (Hilt entry point unavailable) is a safe no-op.
 *
 * Top-level and `internal` so the invalidation behavior can be unit-tested without instantiating
 * the service.
 */
internal fun invalidateCache(cache: AccessibilityNodeCache?) {
    cache?.clear()
}
