package com.danielealbano.androidremotecontrolmcp.services.im.render

/**
 * Strips the inline Markdown a channel without Markdown rendering would otherwise show literally:
 * bold, italic, strikethrough, inline code, links, images and loose HTML tags. Word-internal
 * underscores (`snake_case_name`), a lone multiplication `*` (`2 * 3`) and a glob (`*.kt`) are left
 * untouched, same as [com.danielealbano.androidremotecontrolmcp.services.chat.MarkdownText]; the
 * leading-dot guard on italics is what keeps `*.kt` from being read as an opening marker.
 */
internal object InlineMarkdown {
    private const val G_IMAGE_ALT = 1
    private const val G_IMAGE_URL = 2
    private const val G_LINK_TEXT = 3
    private const val G_LINK_URL = 4
    private const val G_CODE = 5
    private const val G_BOLD_STARS = 6
    private const val G_BOLD_UNDERSCORES = 7
    private const val G_STRIKE = 8
    private const val G_ITALIC_STAR = 9
    private const val G_ITALIC_UNDERSCORE = 10

    private val MARKERS =
        Regex(
            "!\\[([^\\]]*)]\\(([^)\\s]+)\\)" +
                "|\\[([^\\]]+)]\\(([^)\\s]+)\\)" +
                "|`([^`]+)`" +
                "|\\*\\*(.+?)\\*\\*" +
                "|__(.+?)__" +
                "|~~(.+?)~~" +
                "|(?<![\\w*])\\*(?!\\s)(?!\\.)(.+?)(?<!\\s)\\*(?![\\w*])" +
                "|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)" +
                "|</?[A-Za-z][^<>]*>",
        )

    fun strip(text: String): String {
        val out = StringBuilder()
        var last = 0
        MARKERS.findAll(text).forEach { match ->
            out.append(text, last, match.range.first)
            out.append(replacement(match.groupValues))
            last = match.range.last + 1
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    private fun replacement(groups: List<String>): String =
        when {
            groups[G_IMAGE_URL].isNotEmpty() -> "[图片：${groups[G_IMAGE_ALT]}]（${groups[G_IMAGE_URL]}）"
            groups[G_LINK_URL].isNotEmpty() -> linkText(groups[G_LINK_TEXT], groups[G_LINK_URL])
            groups[G_CODE].isNotEmpty() -> groups[G_CODE]
            groups[G_BOLD_STARS].isNotEmpty() -> groups[G_BOLD_STARS]
            groups[G_BOLD_UNDERSCORES].isNotEmpty() -> groups[G_BOLD_UNDERSCORES]
            groups[G_STRIKE].isNotEmpty() -> groups[G_STRIKE]
            groups[G_ITALIC_STAR].isNotEmpty() -> groups[G_ITALIC_STAR]
            groups[G_ITALIC_UNDERSCORE].isNotEmpty() -> groups[G_ITALIC_UNDERSCORE]
            else -> ""
        }

    private fun linkText(
        text: String,
        url: String,
    ): String = if (text == url) url else "$text（$url）"
}
