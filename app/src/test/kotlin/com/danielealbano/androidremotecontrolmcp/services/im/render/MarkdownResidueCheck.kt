package com.danielealbano.androidremotecontrolmcp.services.im.render

/**
 * Shared assertion helper for [SampleRepliesTest]: a text part rendered for a channel that cannot
 * show Markdown must never leak Markdown syntax that channel would show literally.
 */
internal object MarkdownResidueCheck {
    private val RESIDUE_MARKERS =
        listOf(
            "**",
            "~~",
            "](",
            "|---",
            "`",
        )
    private val HEADING_LINE = Regex("(?m)^#{1,6}\\s")

    /** Null when [text] is clean; otherwise the marker that gave it away, for a useful failure message. */
    fun residueIn(text: String): String? {
        val marker = RESIDUE_MARKERS.firstOrNull { text.contains(it) }
        if (marker != null) return marker
        return if (HEADING_LINE.containsMatchIn(text)) "heading marker" else null
    }
}
