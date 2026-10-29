package com.danielealbano.androidremotecontrolmcp.ui.navigation

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Decides whether one horizontal drag means "go back": it has to end far enough to the right.
 * Leftward drags never count, and a drag that turns back before release is judged by where it ends.
 */
class SwipeBackTracker(
    private val thresholdPx: Float,
) {
    private var total = 0f

    fun start() {
        total = 0f
    }

    /** Adds a step; true while the drag is heading right, i.e. while it is ours to consume. */
    fun drag(dx: Float): Boolean {
        total += dx
        return total > 0f
    }

    fun shouldGoBack(): Boolean = total >= thresholdPx
}

/**
 * Swiping right across a page goes back, the way the system back gesture would. Children that
 * handle horizontal drags themselves (sliders, text fields) get them first and win.
 */
fun Modifier.swipeToGoBack(onBack: () -> Unit): Modifier =
    pointerInput(onBack) {
        val tracker = SwipeBackTracker(SWIPE_BACK_DP.dp.toPx())
        detectHorizontalDragGestures(
            onDragStart = { tracker.start() },
            onDragEnd = { if (tracker.shouldGoBack()) onBack() },
            onDragCancel = { tracker.start() },
        ) { change, dx -> if (tracker.drag(dx)) change.consume() }
    }

private const val SWIPE_BACK_DP = 72
