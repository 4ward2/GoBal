package com.danielealbano.androidremotecontrolmcp.services.skill

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData

/** A control found on screen: [node] matched the selector, [clickTarget] is what receives the click. */
data class NodeHit(
    val node: AccessibilityNodeData,
    val clickTarget: AccessibilityNodeData,
    val label: String,
)

enum class HitPurpose {
    /** Something to tap: input fields are skipped unless the selector asks for one by class. */
    TAP,

    /** An input field. */
    INPUT,

    /** Anything visible. */
    ANY,
}

/** Finds controls by what they say, never by position. Pure; works on a parsed snapshot. */
object SelectorMatcher {
    private const val INPUT_METHOD = "INPUT_METHOD"

    fun find(
        windows: List<WindowData>,
        selector: NodeSelector,
        purpose: HitPurpose,
    ): NodeHit? {
        val candidates = mutableListOf<NodeHit>()
        scope(windows, selector.packageName).forEach { w ->
            collect(w.tree, mutableListOf()) { node, ancestors ->
                if (fits(node, selector, purpose)) {
                    val target = if (node.clickable) node else ancestors.lastOrNull { it.clickable } ?: node
                    candidates += NodeHit(node, target, labelOf(node))
                }
            }
        }
        val wanted = selector.text?.let(::norm)
        // An exact label beats one that merely contains it; otherwise the first on screen, as a
        // person would pick from a list (not the shortest label: that picks an arbitrary shop).
        return candidates.minWithOrNull(
            compareBy<NodeHit> { if (wanted != null && norm(it.label) == wanted) 0 else 1 }
                .thenBy { it.node.bounds.top }
                .thenBy { it.node.bounds.left },
        )
    }

    /** First input field on screen (for a Type step without a target). */
    fun firstInput(
        windows: List<WindowData>,
        packageName: String?,
    ): NodeHit? {
        var hit: NodeHit? = null
        scope(windows, packageName).forEach { w ->
            collect(w.tree, mutableListOf()) { node, _ ->
                if (hit == null && node.isUsableInput()) hit = NodeHit(node, node, labelOf(node))
            }
        }
        return hit
    }

    /** Whether any visible control shows [text] (whitespace and case ignored). */
    fun screenShows(
        windows: List<WindowData>,
        text: String,
    ): Boolean {
        val wanted = squeeze(text)
        if (wanted.isEmpty()) return false
        var found = false
        windows.filter { it.windowType != INPUT_METHOD }.forEach { w ->
            collect(w.tree, mutableListOf()) { node, _ ->
                if (!found && node.visible) {
                    found = squeeze(node.text.orEmpty()).contains(wanted) ||
                        squeeze(node.contentDescription.orEmpty()).contains(wanted)
                }
            }
        }
        return found
    }

    /** Whether a window of [packageName] is on screen. */
    fun showsPackage(
        windows: List<WindowData>,
        packageName: String,
    ): Boolean = windows.any { it.packageName == packageName && it.windowType != INPUT_METHOD }

    fun labelOf(node: AccessibilityNodeData): String =
        node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription?.takeIf { it.isNotBlank() } ?: ""

    fun squeeze(text: String): String = text.filterNot { it.isWhitespace() }.lowercase()

    private fun norm(text: String): String = text.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun scope(
        windows: List<WindowData>,
        packageName: String?,
    ): List<WindowData> =
        windows.filter {
            it.windowType != INPUT_METHOD && (packageName.isNullOrBlank() || it.packageName == packageName)
        }

    private fun fits(
        node: AccessibilityNodeData,
        selector: NodeSelector,
        purpose: HitPurpose,
    ): Boolean {
        val b = node.bounds
        val onScreen = node.visible && b.right > b.left && b.bottom > b.top
        val wantsInput = selector.className?.contains("Edit", ignoreCase = true) == true
        val purposeOk =
            when (purpose) {
                HitPurpose.TAP -> !node.editable || wantsInput
                HitPurpose.INPUT -> node.editable
                HitPurpose.ANY -> true
            }
        return onScreen && purposeOk && !selector.isEmpty && textFits(node, selector) &&
            (selector.resourceId.isNullOrBlank() || node.resourceId == selector.resourceId) &&
            (selector.className.isNullOrBlank() || node.className?.endsWith(selector.className) == true)
    }

    private fun textFits(
        node: AccessibilityNodeData,
        selector: NodeSelector,
    ): Boolean {
        val wanted = selector.text?.takeIf { it.isNotBlank() } ?: return true
        val labels = listOfNotNull(node.text, node.contentDescription).filter { it.isNotBlank() }
        return when (selector.match) {
            TextMatch.EXACT -> labels.any { norm(it) == norm(wanted) }
            TextMatch.CONTAINS -> labels.any { squeeze(it).contains(squeeze(wanted)) }
        }
    }
}

/** A field a Type step can write into. */
private fun AccessibilityNodeData.isUsableInput(): Boolean = editable && visible && enabled

/** Depth-first walk; [ancestors] is the live path from the root, valid only during [visit]. */
private fun collect(
    node: AccessibilityNodeData,
    ancestors: MutableList<AccessibilityNodeData>,
    visit: (AccessibilityNodeData, List<AccessibilityNodeData>) -> Unit,
) {
    visit(node, ancestors)
    ancestors.add(node)
    node.children.forEach { collect(it, ancestors, visit) }
    ancestors.removeAt(ancestors.lastIndex)
}
