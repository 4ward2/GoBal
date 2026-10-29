package com.danielealbano.androidremotecontrolmcp.services.im.render

/**
 * The plain-text fallback for a GFM pipe table: one "column：value" line per cell, data rows
 * separated by a blank line. [ChannelRenderer] keeps the original Markdown as the picture source
 * for channels that can draw the table instead.
 */
internal object TableText {
    private const val HEADER_AND_SEPARATOR = 2
    private const val ESCAPED_PIPE_PLACEHOLDER = "\u0000"

    fun fallback(lines: List<String>): String {
        if (lines.size < HEADER_AND_SEPARATOR) return ""
        val header = cells(lines[0]).map { InlineMarkdown.strip(it) }
        val rows = lines.drop(HEADER_AND_SEPARATOR).map { row -> cells(row).map { InlineMarkdown.strip(it) } }
        val summary = "（表格，共 ${rows.size} 行）"
        val body = rows.joinToString("\n\n") { row -> rowText(header, row) }
        return if (body.isEmpty()) summary else "$summary\n\n$body"
    }

    private fun rowText(
        header: List<String>,
        row: List<String>,
    ): String = header.indices.joinToString("\n") { "${header[it]}：${row.getOrElse(it) { "" }}" }

    private fun cells(row: String): List<String> {
        val trimmed = row.trim().removePrefix("|").removeSuffix("|")
        val protected = trimmed.replace("\\|", ESCAPED_PIPE_PLACEHOLDER)
        return protected.split("|").map { it.replace(ESCAPED_PIPE_PLACEHOLDER, "|").trim() }
    }
}
