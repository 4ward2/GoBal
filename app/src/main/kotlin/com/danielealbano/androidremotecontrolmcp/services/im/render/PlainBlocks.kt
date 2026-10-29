package com.danielealbano.androidremotecontrolmcp.services.im.render

/**
 * Turns a run of ordinary Markdown lines (headings, lists, quotes, rules, loose HTML) into plain
 * text for channels that cannot render Markdown themselves. Table, code and formula blocks are
 * handled separately, by [TableText], [CodeText] and [FormulaText].
 */
internal object PlainBlocks {
    private const val INDENT_WIDTH = 2
    private val BULLET_MARKERS = listOf("• ", "◦ ", "▪ ")
    private val BR_TAG = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val HEADING = Regex("^#{1,6}\\s+(.*)$")
    private val TASK_ITEM = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+(.*)$")
    private val BULLET_ITEM = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED_ITEM = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val QUOTE = Regex("^(\\s*(?:>\\s*)+)(.*)$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    /** [lines] with every marker gone; headings get a blank line of their own around them. */
    fun render(lines: List<String>): String {
        val expanded = lines.flatMap { it.replace(BR_TAG, "\n").split("\n") }
        return expanded.flatMap { renderLine(it) }.joinToString("\n")
    }

    private fun renderLine(line: String): List<String> {
        val heading = HEADING.find(line)
        val task = TASK_ITEM.find(line)
        val bullet = BULLET_ITEM.find(line)
        val numbered = NUMBERED_ITEM.find(line)
        val quote = QUOTE.find(line)
        return when {
            RULE.matches(line) -> emptyList()
            heading != null -> headingLines(heading)
            task != null -> listOf(taskLine(task))
            bullet != null -> listOf(bulletLine(bullet))
            numbered != null -> listOf(numberedLine(numbered))
            quote != null -> listOf(quoteLine(quote))
            else -> listOf(InlineMarkdown.strip(line))
        }
    }

    private fun headingLines(match: MatchResult): List<String> {
        val text = InlineMarkdown.strip(match.groupValues[1]).trim()
        return listOf("", text, "")
    }

    private fun taskLine(match: MatchResult): String {
        val (indent, mark, rest) = match.destructured
        val box = if (mark.equals("x", ignoreCase = true)) "☑ " else "☐ "
        return prefix(indent) + box + InlineMarkdown.strip(rest)
    }

    private fun bulletLine(match: MatchResult): String {
        val (indent, rest) = match.destructured
        return prefix(indent) + marker(level(indent)) + InlineMarkdown.strip(rest)
    }

    private fun numberedLine(match: MatchResult): String {
        val (indent, number, rest) = match.destructured
        return prefix(indent) + "$number. " + InlineMarkdown.strip(rest)
    }

    private fun quoteLine(match: MatchResult): String {
        val depth = match.groupValues[1].count { it == '>' }
        return "｜ ".repeat(depth) + InlineMarkdown.strip(match.groupValues[2])
    }

    private fun level(indent: String): Int = indent.length / INDENT_WIDTH

    private fun prefix(indent: String): String = "  ".repeat(level(indent))

    private fun marker(level: Int): String = BULLET_MARKERS.getOrElse(level) { BULLET_MARKERS.last() }
}
