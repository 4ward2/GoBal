package com.danielealbano.androidremotecontrolmcp.services.im.render

/** The paragraph-, table-, code- and formula-level chunks [ChannelRenderer] treats differently. */
internal sealed interface MarkdownBlock {
    data class Lines(
        val lines: List<String>,
    ) : MarkdownBlock

    data class Table(
        val lines: List<String>,
    ) : MarkdownBlock

    data class Code(
        val language: String,
        val lines: List<String>,
        val closed: Boolean,
    ) : MarkdownBlock

    data class Formula(
        val lines: List<String>,
    ) : MarkdownBlock
}

/**
 * Splits a reply into [MarkdownBlock]s without looking inside fenced code (so a stray `|` or `$$`
 * in example code is never mistaken for a table or a formula). A fence left open by a reply that
 * was cut short still closes at the end of the text instead of throwing; [MarkdownBlock.Code.closed]
 * says whether that happened.
 */
internal object MarkdownBlocks {
    private val FENCE_OPEN = Regex("^\\s*```\\s*([\\w+#.-]*)\\s*$")
    private val FENCE_CLOSE = Regex("^\\s*```\\s*$")
    private val SEPARATOR_CELL = Regex("^:?-+:?$")
    private val DOLLAR_SINGLE_LINE = Regex("^\\$\\$.+\\$\\$$")
    private val BRACKET_SINGLE_LINE = Regex("^\\\\\\[.+\\\\\\]$")

    fun parse(text: String): List<MarkdownBlock> {
        val lines = text.replace("\r\n", "\n").split("\n")
        val out = mutableListOf<MarkdownBlock>()
        val pending = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val special = startSpecial(lines, i)
            if (special == null) {
                pending += lines[i]
                i++
                continue
            }
            if (pending.isNotEmpty()) {
                out += MarkdownBlock.Lines(pending.toList())
                pending.clear()
            }
            out += special.first
            i = special.second
        }
        if (pending.isNotEmpty()) out += MarkdownBlock.Lines(pending.toList())
        return out
    }

    private fun startSpecial(
        lines: List<String>,
        i: Int,
    ): Pair<MarkdownBlock, Int>? {
        val line = lines[i]
        val fence = FENCE_OPEN.find(line)
        return when {
            fence != null -> readCode(lines, i, fence.groupValues[1])
            isTableStart(lines, i) -> readTable(lines, i)
            isFormulaStart(line) -> readFormula(lines, i)
            else -> null
        }
    }

    private fun readCode(
        lines: List<String>,
        start: Int,
        language: String,
    ): Pair<MarkdownBlock, Int> {
        val body = mutableListOf<String>()
        var i = start + 1
        while (i < lines.size && !FENCE_CLOSE.matches(lines[i])) {
            body += lines[i]
            i++
        }
        return MarkdownBlock.Code(language, body.toList(), closed = i < lines.size) to (i + 1)
    }

    private fun isTableStart(
        lines: List<String>,
        i: Int,
    ): Boolean = i + 1 < lines.size && isTableRow(lines[i]) && isTableSeparator(lines[i + 1])

    private fun readTable(
        lines: List<String>,
        start: Int,
    ): Pair<MarkdownBlock, Int> {
        val body = mutableListOf(lines[start], lines[start + 1])
        var i = start + 2
        while (i < lines.size && isTableRow(lines[i])) {
            body += lines[i]
            i++
        }
        return MarkdownBlock.Table(body.toList()) to i
    }

    private fun isTableRow(line: String): Boolean = line.isNotBlank() && line.contains('|')

    private fun isTableSeparator(line: String): Boolean {
        val cells =
            line
                .trim()
                .removePrefix("|")
                .removeSuffix("|")
                .split("|")
        return cells.isNotEmpty() && cells.all { SEPARATOR_CELL.matches(it.trim()) }
    }

    private fun isFormulaStart(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed == "$$" || trimmed == "\\[" ||
            DOLLAR_SINGLE_LINE.matches(trimmed) || BRACKET_SINGLE_LINE.matches(trimmed)
    }

    private fun readFormula(
        lines: List<String>,
        start: Int,
    ): Pair<MarkdownBlock, Int> {
        val trimmed = lines[start].trim()
        val singleLine = DOLLAR_SINGLE_LINE.matches(trimmed) || BRACKET_SINGLE_LINE.matches(trimmed)
        if (singleLine) return MarkdownBlock.Formula(listOf(lines[start])) to (start + 1)
        val closing = if (trimmed == "$$") "$$" else "\\]"
        val body = mutableListOf(lines[start])
        var i = start + 1
        while (i < lines.size && lines[i].trim() != closing) {
            body += lines[i]
            i++
        }
        if (i < lines.size) body += lines[i]
        return MarkdownBlock.Formula(body.toList()) to (i + 1)
    }
}
