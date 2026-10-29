package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import com.danielealbano.androidremotecontrolmcp.services.chat.MarkdownText
import com.danielealbano.androidremotecontrolmcp.services.chat.Run

/** Colours the Markdown styles borrow from the bubble they sit in. */
data class MarkdownPalette(
    val link: Color,
    val codeBackground: Color,
)

/** Turns a reply's Markdown into styled text for a bubble. */
object ChatMarkdown {
    private val HEADING_SIZE = 1.15.em

    fun annotate(
        text: String,
        palette: MarkdownPalette,
    ): AnnotatedString =
        buildAnnotatedString {
            MarkdownText.runs(text).forEach { run -> appendRun(run, palette) }
        }

    /** [text] with every occurrence of [query] marked in [color]; unchanged when there is none. */
    fun highlight(
        text: AnnotatedString,
        query: String,
        color: Color,
    ): AnnotatedString {
        val ranges = ChatSearch.ranges(text.text, query.trim())
        if (ranges.isEmpty()) return text
        return buildAnnotatedString {
            append(text)
            ranges.forEach { addStyle(SpanStyle(background = color), it.first, it.last + 1) }
        }
    }

    private fun AnnotatedString.Builder.appendRun(
        run: Run,
        palette: MarkdownPalette,
    ) {
        val link = run.link
        if (link != null) {
            val styles = TextLinkStyles(SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline))
            withLink(LinkAnnotation.Url(link, styles)) { append(run.text) }
            return
        }
        withStyle(styleOf(run, palette)) { append(run.text) }
    }

    private fun styleOf(
        run: Run,
        palette: MarkdownPalette,
    ): SpanStyle =
        SpanStyle(
            fontWeight = if (run.bold) FontWeight.Bold else null,
            fontStyle = if (run.italic) FontStyle.Italic else null,
            fontFamily = if (run.code) FontFamily.Monospace else null,
            background = if (run.code) palette.codeBackground else Color.Unspecified,
            textDecoration = if (run.strike) TextDecoration.LineThrough else null,
            fontSize = if (run.heading) HEADING_SIZE else androidx.compose.ui.unit.TextUnit.Unspecified,
        )
}

/** The styled text, computed once per bubble and reused across recompositions. */
@Composable
fun rememberMarkdown(
    text: String,
    palette: MarkdownPalette,
): AnnotatedString = remember(text, palette) { ChatMarkdown.annotate(text, palette) }
