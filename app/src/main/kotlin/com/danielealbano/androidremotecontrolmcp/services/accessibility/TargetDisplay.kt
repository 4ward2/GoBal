package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.Display

/**
 * The display that screen-reading and gesture tools act on.
 *
 * [Display.DEFAULT_DISPLAY] is the physical screen. Any other id is a shadow (virtual)
 * display where an app runs in the background while the user keeps using the physical
 * screen. Tree reads use `getWindowsOnAllDisplays()[id]`, screenshots use
 * `takeScreenshot(id, …)` and gestures use `GestureDescription.Builder.setDisplayId(id)`,
 * so every existing tool follows the target without per-tool parameters.
 *
 * Process-wide by design: one agent drives the device at a time. Switching target is an
 * explicit tool call (`set_target_display` / the shadow tools), never implicit.
 */
object TargetDisplay {
    @Volatile
    var id: Int = Display.DEFAULT_DISPLAY
        private set

    val isDefault: Boolean
        get() = id == Display.DEFAULT_DISPLAY

    fun set(displayId: Int) {
        require(displayId >= 0) { "display id must be >= 0, was $displayId" }
        id = displayId
    }

    fun reset() {
        id = Display.DEFAULT_DISPLAY
    }
}
