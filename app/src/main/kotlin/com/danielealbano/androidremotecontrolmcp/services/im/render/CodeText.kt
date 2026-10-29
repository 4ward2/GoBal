package com.danielealbano.androidremotecontrolmcp.services.im.render

/**
 * Decides whether a fenced code block is long enough to deserve its own picture ([LONG_LINE_COUNT]
 * lines or a line wider than [LONG_LINE_WIDTH] display columns), and renders the plain-text form
 * used as a picture's fallback or sent directly when the channel cannot carry a picture at all. A
 * short block is never turned into a picture; [ChannelRenderer] sends it as plain text verbatim.
 */
internal object CodeText {
    const val LONG_LINE_COUNT = 8
    const val LONG_LINE_WIDTH = 40

    private const val CJK_START = 0x2E80
    private const val CJK_END = 0x9FFF
    private const val HANGUL_START = 0xAC00
    private const val HANGUL_END = 0xD7AF
    private const val FULLWIDTH_START = 0xFF00
    private const val FULLWIDTH_END = 0xFFEF
    private const val WIDE_CHAR_WIDTH = 2
    private const val NARROW_CHAR_WIDTH = 1

    fun isLong(block: MarkdownBlock.Code): Boolean =
        block.lines.size > LONG_LINE_COUNT || block.lines.any { displayWidth(it) > LONG_LINE_WIDTH }

    /** A picture's fallback, and the text sent directly when the channel has no pictures. */
    fun plain(block: MarkdownBlock.Code): String {
        val header = block.language.takeIf { it.isNotEmpty() }?.let { "[代码：$it]\n" } ?: ""
        return header + block.lines.joinToString("\n")
    }

    /** The block's Markdown, rebuilt with its fence, so it can be drawn as a picture. */
    fun source(block: MarkdownBlock.Code): String {
        val close = if (block.closed) "\n```" else ""
        return "```${block.language}\n${block.lines.joinToString("\n")}$close"
    }

    private fun displayWidth(line: String): Int = line.sumOf { if (isWide(it)) WIDE_CHAR_WIDTH else NARROW_CHAR_WIDTH }

    private fun isWide(ch: Char): Boolean {
        val code = ch.code
        return code in CJK_START..CJK_END || code in HANGUL_START..HANGUL_END || code in FULLWIDTH_START..FULLWIDTH_END
    }
}
