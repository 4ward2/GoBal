package com.danielealbano.androidremotecontrolmcp.services.im.render

/**
 * The plain-text fallback for a block formula: the LaTeX content without its `$$` or `\[...\]`
 * delimiters. [ChannelRenderer] keeps the delimited original as the picture source; the content
 * itself is never run through [InlineMarkdown], since `_` and `*` are ordinary LaTeX characters.
 */
internal object FormulaText {
    private val DOLLAR_SINGLE_LINE = Regex("^\\$\\$(.+)\\$\\$$")
    private val BRACKET_SINGLE_LINE = Regex("^\\\\\\[(.+)\\\\\\]$")

    fun fallback(lines: List<String>): String {
        val single = lines.singleOrNull()?.trim()?.let(::matchSingleLine)
        if (single != null) return single.groupValues[1].trim()
        val closed = lines.size > 1 && isClosingDelimiter(lines.last())
        val body = if (closed) lines.subList(1, lines.size - 1) else lines.drop(1)
        return body.joinToString("\n").trim()
    }

    private fun matchSingleLine(line: String): MatchResult? {
        val dollar = DOLLAR_SINGLE_LINE.find(line)
        return dollar ?: BRACKET_SINGLE_LINE.find(line)
    }

    private fun isClosingDelimiter(line: String): Boolean = line.trim().let { it == "$$" || it == "\\]" }
}
