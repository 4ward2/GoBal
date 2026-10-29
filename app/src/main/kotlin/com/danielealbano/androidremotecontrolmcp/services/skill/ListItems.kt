package com.danielealbano.androidremotecontrolmcp.services.skill

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import kotlinx.serialization.Serializable
import java.util.IdentityHashMap

/** One entry of a list as the user sees it: its texts in reading order. */
@Serializable
data class CollectedItem(
    val texts: List<String>,
) {
    /** The texts on one line, with price fragments glued back ("¥ 3 .3" → "¥3.3"). */
    val line: String get() = ListItems.line(texts)
}

/**
 * Finds the entries of the main list on screen without knowing the app: the list is the parent
 * with the most wide, labelled children (cards span most of the screen; carousel tiles and
 * filter chips do not). Only entries entirely on screen are taken, so an entry cut by the screen
 * edge is not stored half-read; scrolling less than a screen brings it in whole on the next pass.
 */
object ListItems {
    private const val MIN_WIDTH_SHARE = 0.5
    private const val MIN_TEXTS = 2
    private val NUMBER_PART = Regex("^[.．]?\\d+$")

    fun extract(windows: List<WindowData>): List<CollectedItem> {
        val window =
            windows.filter { it.windowType == "APPLICATION" }.maxByOrNull { area(it.tree) } ?: return emptyList()
        val screen = window.tree.bounds
        val minWidth = (screen.right - screen.left) * MIN_WIDTH_SHARE
        val texts = IdentityHashMap<AccessibilityNodeData, List<String>>()
        collectTexts(window.tree, texts)
        var best: List<AccessibilityNodeData> = emptyList()
        forEachNode(window.tree) { parent ->
            val cards =
                parent.children.filter { c ->
                    c.visible && (c.bounds.right - c.bounds.left) >= minWidth && texts[c].orEmpty().size >= MIN_TEXTS
                }
            // Ties go to the deeper parent: an outer wrapper holding the list and one banner has
            // as few wide children as a short list, and the list is what is wanted.
            if (cards.isNotEmpty() && cards.size >= best.size) best = cards
        }
        return best
            .filter { it.bounds.top >= screen.top && it.bounds.bottom <= screen.bottom }
            .filter { it.bounds.bottom > it.bounds.top }
            .map { CollectedItem(texts[it].orEmpty()) }
    }

    fun line(texts: List<String>): String =
        buildString {
            texts.forEachIndexed { i, t ->
                val afterCurrency = i > 0 && (texts[i - 1] == "¥" || texts[i - 1] == "￥")
                val decimals = i > 0 && NUMBER_PART.matches(t) && t.startsWith(".")
                val glue = afterCurrency || decimals
                if (i > 0 && !glue) append(' ')
                append(t)
            }
        }

    /** Labels under each node, in reading order, without the text/description repeats RN emits. */
    private fun collectTexts(
        node: AccessibilityNodeData,
        out: MutableMap<AccessibilityNodeData, List<String>>,
    ): List<String> {
        val own = SelectorMatcher.labelOf(node).trim().takeIf { node.visible && it.isNotEmpty() }
        val below = node.children.flatMap { collectTexts(it, out) }
        val all = if (own != null && own !in below) listOf(own) + below else below
        val deduped = all.filterIndexed { i, t -> i == 0 || t != all[i - 1] }
        out[node] = deduped
        return deduped
    }

    private fun forEachNode(
        node: AccessibilityNodeData,
        visit: (AccessibilityNodeData) -> Unit,
    ) {
        visit(node)
        node.children.forEach { forEachNode(it, visit) }
    }

    private fun area(node: AccessibilityNodeData): Long =
        (node.bounds.right - node.bounds.left).toLong() * (node.bounds.bottom - node.bounds.top)
}
