package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import com.danielealbano.androidremotecontrolmcp.services.vault.ContentRedactor

/**
 * Text is scrubbed by [ContentRedactor], but pixels are not: a screenshot taken after a vault
 * fill would show the phone number in the field. This paints the on-screen bounds of every
 * node whose text or description contains a vault value black before the image leaves the
 * device. Only the innermost matching nodes are masked, so a web page whose root node
 * aggregates all text does not black out the whole screen.
 */
object VaultScreenshotMask {
    fun secretBounds(
        windows: List<WindowData>,
        redactor: ContentRedactor,
    ): List<BoundsData> {
        val out = mutableListOf<BoundsData>()
        windows.forEach { collect(it.tree, redactor, out) }
        return out
    }

    /** Masks in place; [bitmap] must be mutable. Returns the number of masked regions. */
    fun apply(
        bitmap: Bitmap,
        windows: List<WindowData>,
        screenWidth: Int,
        screenHeight: Int,
        redactor: ContentRedactor,
    ): Int {
        val boxes = secretBounds(windows, redactor)
        if (boxes.isEmpty() || screenWidth <= 0 || screenHeight <= 0) return 0
        val canvas = Canvas(bitmap)
        val paint =
            Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }
        val sx = bitmap.width.toFloat() / screenWidth
        val sy = bitmap.height.toFloat() / screenHeight
        boxes.forEach { canvas.drawRect(it.left * sx, it.top * sy, it.right * sx, it.bottom * sy, paint) }
        return boxes.size
    }

    /** Returns true when [node] or a descendant matched; adds bounds for innermost matches only. */
    private fun collect(
        node: AccessibilityNodeData,
        redactor: ContentRedactor,
        out: MutableList<BoundsData>,
    ): Boolean {
        var childMatched = false
        node.children.forEach { if (collect(it, redactor, out)) childMatched = true }
        val matches = listOfNotNull(node.text, node.contentDescription).any { redactor.redact(it) != it }
        if (matches && !childMatched && node.visible) out.add(node.bounds)
        return matches || childMatched
    }
}
