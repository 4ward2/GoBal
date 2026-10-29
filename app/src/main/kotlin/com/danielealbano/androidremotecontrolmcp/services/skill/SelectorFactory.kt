package com.danielealbano.androidremotecontrolmcp.services.skill

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData

/** A selector for a control, or why none can find it again. */
sealed interface SelectorResult {
    data class Found(
        val selector: NodeSelector,
    ) : SelectorResult

    data class Unusable(
        val reason: String,
    ) : SelectorResult
}

/**
 * Turns a control the model acted on (by node id or by a tap position) into a [NodeSelector] that
 * finds the same control again on a later run: by its words, the words of its first labelled child,
 * or its resource id. A selector that would pick another control first is refused rather than
 * stored, since a replay would then tap the wrong thing.
 */
object SelectorFactory {
    private const val MAX_EXACT_LABEL = 24
    private val LABEL_SPLIT = Regex("[\\s,，|·/]+")

    fun forNode(
        windows: List<WindowData>,
        nodeId: String,
        purpose: HitPurpose = HitPurpose.TAP,
    ): SelectorResult {
        val found = locate(windows) { it.id == nodeId } ?: return SelectorResult.Unusable("控件 $nodeId 已不在屏幕上")
        return build(windows, found, purpose)
    }

    /** The deepest visible control under a tap at ([x], [y]). */
    fun atPoint(
        windows: List<WindowData>,
        x: Float,
        y: Float,
    ): SelectorResult {
        var best: Located? = null
        windows.filter { it.windowType != "INPUT_METHOD" }.forEach { w ->
            walk(w.tree, emptyList()) { node, path ->
                if (node.visible && covers(node.bounds, x, y)) {
                    if (best == null || path.size >= best!!.path.size) best = Located(w, node, path)
                }
            }
        }
        val hit = best ?: return SelectorResult.Unusable("点的位置 (${x.toInt()},${y.toInt()}) 没有控件")
        return build(windows, hit, HitPurpose.TAP)
    }

    /**
     * Short labels are kept exactly. Long ones (a card's "蜜雪冰城 4.8分 月售9999 1.2km") change
     * from run to run, so only their first part is kept and matched by containment.
     */
    fun stableLabel(label: String): Pair<String, TextMatch> {
        val clean = label.trim()
        if (clean.length <= MAX_EXACT_LABEL) return clean to TextMatch.EXACT
        val head = clean.split(LABEL_SPLIT).firstOrNull { it.isNotBlank() } ?: clean
        return head.take(MAX_EXACT_LABEL) to TextMatch.CONTAINS
    }

    private fun covers(
        b: BoundsData,
        x: Float,
        y: Float,
    ): Boolean {
        val inX = x >= b.left && x < b.right
        val inY = y >= b.top && y < b.bottom
        return inX && inY
    }

    private data class Located(
        val window: WindowData,
        val node: AccessibilityNodeData,
        val path: List<AccessibilityNodeData>,
    )

    private fun build(
        windows: List<WindowData>,
        at: Located,
        purpose: HitPurpose,
    ): SelectorResult {
        val clickable = (at.path + at.node).any { it.clickable }
        // Words from inside only name a clickable card; a tap on a page background is not "the first label on it".
        val labelled = labelledNode(at.node)?.takeIf { it === at.node || clickable }
        val label = labelled?.let { SelectorMatcher.labelOf(it) }?.takeIf { it.isNotBlank() }
        val id = at.node.resourceId?.takeIf { it.isNotBlank() }
        if (label == null && id == null) return SelectorResult.Unusable("控件没有文字也没有资源 id，重放时认不出来")
        val (text, match) = label?.let(::stableLabel) ?: (null to TextMatch.EXACT)
        val selector =
            NodeSelector(
                text = text,
                match = match,
                // The id and the class must belong to the node that carries the text.
                resourceId = id.takeIf { label == null || labelled === at.node },
                className = labelled?.className?.takeIf { label != null },
                packageName = at.window.packageName,
            )
        return if (picksSame(windows, selector, at, purpose)) {
            SelectorResult.Found(selector)
        } else {
            SelectorResult.Unusable("屏幕上有好几个「${text ?: id}」，重放时分不清是哪一个")
        }
    }

    /** Whether the selector finds this control (or something inside the same clickable target) first. */
    private fun picksSame(
        windows: List<WindowData>,
        selector: NodeSelector,
        at: Located,
        purpose: HitPurpose,
    ): Boolean {
        val hit = SelectorMatcher.find(windows, selector, purpose) ?: return false
        val target = (at.path + at.node).lastOrNull { it.clickable } ?: at.node
        return hit.node === at.node || hit.clickTarget === target || isInside(hit.node, at.node)
    }

    private fun isInside(
        node: AccessibilityNodeData,
        container: AccessibilityNodeData,
    ): Boolean = container.children.any { it === node || isInside(node, it) }

    /** The node itself when it has words, otherwise its first labelled descendant (breadth first). */
    private fun labelledNode(node: AccessibilityNodeData): AccessibilityNodeData? {
        if (SelectorMatcher.labelOf(node).isNotBlank()) return node
        val queue = ArrayDeque(node.children)
        var found: AccessibilityNodeData? = null
        while (found == null && queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.visible && SelectorMatcher.labelOf(n).isNotBlank()) {
                found = n
            } else {
                queue.addAll(n.children)
            }
        }
        return found
    }

    private fun locate(
        windows: List<WindowData>,
        test: (AccessibilityNodeData) -> Boolean,
    ): Located? {
        var found: Located? = null
        windows.forEach { w ->
            walk(w.tree, emptyList()) { node, path -> if (found == null && test(node)) found = Located(w, node, path) }
        }
        return found
    }

    private fun walk(
        node: AccessibilityNodeData,
        path: List<AccessibilityNodeData>,
        visit: (AccessibilityNodeData, List<AccessibilityNodeData>) -> Unit,
    ) {
        visit(node, path)
        val next = path + node
        node.children.forEach { walk(it, next, visit) }
    }
}
