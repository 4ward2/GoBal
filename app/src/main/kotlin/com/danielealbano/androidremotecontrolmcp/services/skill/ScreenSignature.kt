package com.danielealbano.androidremotecontrolmcp.services.skill

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData

/** A cheap fingerprint of what is on screen: which windows, and the labels visible in them. */
object ScreenSignature {
    fun of(windows: List<WindowData>): Int {
        var hash = windows.size
        windows.forEach { w ->
            hash = hash * PRIME + w.windowId
            hash = hash * PRIME + (w.activityName?.hashCode() ?: 0)
            hash = hash * PRIME + labels(w.tree)
        }
        return hash
    }

    private fun labels(node: AccessibilityNodeData): Int {
        var hash = if (node.visible) SelectorMatcher.labelOf(node).hashCode() else 0
        node.children.forEach { hash = hash * PRIME + labels(it) }
        return hash
    }

    private const val PRIME = 31
}
