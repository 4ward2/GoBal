package com.danielealbano.androidremotecontrolmcp.services.chat

/** A stretch of text with the styles Markdown asked for; newlines travel as their own runs. */
data class Run(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val heading: Boolean = false,
    val link: String? = null,
)

/** A stretch of a reply: ordinary Markdown text, or the inside of a fenced code block. */
data class Segment(
    val text: String,
    val code: Boolean = false,
    /** The word after the opening fence ("kotlin"), or "" when none was given. */
    val language: String = "",
)

/**
 * The small Markdown subset language models put in chat replies (headings, bullets, numbered
 * lists, bold, italic, inline code, fenced code, strikethrough, links, quotes, rules), parsed into
 * [Run]s so the chat draws styled text instead of raw `**` and `-` markers. Pure Kotlin, no Compose.
 */
object MarkdownText {
    private const val BULLET = "•  "
    private const val QUOTE_BAR = "│ "
    private const val NEWLINE = "\n"
    private val HEADING = Regex("^#{1,6}\\s+(.*)$")
    private val BULLET_ITEM = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED_ITEM = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val QUOTE = Regex("^>\\s?(.*)$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val FENCE = Regex("^\\s*```")
    private val FENCE_LANGUAGE = Regex("^\\s*```\\s*([\\w+#.-]*)")

    // Group numbers of INLINE, in the order the alternatives appear.
    private const val G_BOLD_STARS = 1
    private const val G_BOLD_UNDERSCORES = 2
    private const val G_ITALIC_STAR = 3
    private const val G_ITALIC_UNDERSCORE = 4
    private const val G_CODE = 5
    private const val G_STRIKE = 6
    private const val G_LINK_TEXT = 7
    private const val G_LINK_URL = 8
    private val INLINE =
        Regex(
            "\\*\\*(.+?)\\*\\*" +
                "|__(.+?)__" +
                "|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])" +
                "|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)" +
                "|`([^`]+)`" +
                "|~~(.+?)~~" +
                "|\\[([^\\]]+)]\\(([^)\\s]+)\\)",
        )

    fun runs(text: String): List<Run> {
        val out = mutableListOf<Run>()
        var inFence = false
        shownLines(text).forEachIndexed { index, rawLine ->
            if (index > 0) out += Run(NEWLINE)
            when {
                FENCE.containsMatchIn(rawLine) -> {
                    inFence = !inFence
                    // The fence line itself is not shown, nor the line break that introduced it.
                    out.removeLastOrNull()?.takeIf { it.text != NEWLINE }?.let { out += it }
                }

                inFence -> {
                    out += Run(rawLine, code = true)
                }

                else -> {
                    out += block(rawLine)
                }
            }
        }
        return merge(out)
    }

    /**
     * The lines to draw. A rule is not drawn, and with the empty lines on both sides of it would
     * otherwise leave a hole in the message: outside code, empty lines never come in runs.
     */
    private fun shownLines(text: String): List<String> {
        val out = mutableListOf<String>()
        var inFence = false
        text.replace("\r\n", "\n").lines().forEach { line ->
            val fence = FENCE.containsMatchIn(line)
            // A rule is shown as the gap it makes, and a gap is one empty line however it was written.
            val shown = if (!inFence && !fence && RULE.matches(line)) "" else line
            val repeatedBlank = !inFence && shown.isBlank() && out.lastOrNull()?.isBlank() == true
            if (fence) inFence = !inFence
            if (!repeatedBlank) out += shown
        }
        return out
    }

    /**
     * [text] cut at its code fences, so a code block can be drawn as its own box with a copy
     * button. A fence left open (a reply still being written) runs to the end. Text without any
     * fence comes back as one segment.
     */
    fun segments(text: String): List<Segment> {
        val out = mutableListOf<Segment>()
        val lines = mutableListOf<String>()
        var language: String? = null

        fun flush() {
            val body = lines.joinToString(NEWLINE)
            val open = language
            if (open != null) out += Segment(body, code = true, language = open)
            if (open == null && body.isNotBlank()) out += Segment(body.trim('\n'))
            lines.clear()
        }
        text.replace("\r\n", "\n").lines().forEach { line ->
            if (FENCE.containsMatchIn(line)) {
                flush()
                val named = FENCE_LANGUAGE.find(line)?.groupValues
                language = if (language == null) named?.get(1).orEmpty() else null
            } else {
                lines += line
            }
        }
        flush()
        return out
    }

    /** The text with every marker removed, for quotes, copy and the clipboard. */
    fun plain(text: String): String = runs(text).joinToString("") { it.text }

    private fun block(line: String): List<Run> {
        val heading = HEADING.find(line)
        val bullet = BULLET_ITEM.find(line)
        val numbered = NUMBERED_ITEM.find(line)
        val quote = QUOTE.find(line)
        return when {
            heading != null -> {
                inline(heading.groupValues[1]).map { it.copy(bold = true, heading = true) }
            }

            RULE.matches(line) -> {
                emptyList()
            }

            bullet != null -> {
                listOf(Run(bullet.groupValues[1] + BULLET)) + inline(bullet.groupValues[2])
            }

            numbered != null -> {
                val (indent, number, rest) = numbered.destructured
                listOf(Run("$indent$number. ")) + inline(rest)
            }

            quote != null -> {
                listOf(Run(QUOTE_BAR)) + inline(quote.groupValues[1]).map { it.copy(italic = true) }
            }

            else -> {
                inline(line)
            }
        }
    }

    private fun inline(text: String): List<Run> {
        val out = mutableListOf<Run>()
        var last = 0
        INLINE.findAll(text).forEach { m ->
            if (m.range.first > last) out += Run(text.substring(last, m.range.first))
            out += styled(m)
            last = m.range.last + 1
        }
        if (last < text.length) out += Run(text.substring(last))
        return out
    }

    private fun styled(m: MatchResult): Run {
        val g = m.groupValues
        val bold = g[G_BOLD_STARS].ifEmpty { g[G_BOLD_UNDERSCORES] }
        val italic = g[G_ITALIC_STAR].ifEmpty { g[G_ITALIC_UNDERSCORE] }
        return when {
            bold.isNotEmpty() -> Run(bold, bold = true)
            italic.isNotEmpty() -> Run(italic, italic = true)
            g[G_CODE].isNotEmpty() -> Run(g[G_CODE], code = true)
            g[G_STRIKE].isNotEmpty() -> Run(g[G_STRIKE], strike = true)
            else -> Run(g[G_LINK_TEXT], link = g[G_LINK_URL])
        }
    }

    /** Adjacent runs with the same style become one, and empty runs disappear. */
    private fun merge(runs: List<Run>): List<Run> {
        val out = mutableListOf<Run>()
        runs.filter { it.text.isNotEmpty() }.forEach { run ->
            val prev = out.lastOrNull()
            val sameStyle = prev != null && prev.copy(text = "") == run.copy(text = "")
            val joinable = sameStyle && run.text != NEWLINE && prev?.text != NEWLINE
            if (joinable && prev != null) {
                out[out.lastIndex] = prev.copy(text = prev.text + run.text)
            } else {
                out += run
            }
        }
        return out
    }
}
