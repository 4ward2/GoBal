@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.chat.MarkdownText
import com.danielealbano.androidremotecontrolmcp.services.chat.Segment
import com.danielealbano.androidremotecontrolmcp.ui.theme.SearchMark

/** Puts [text] on the clipboard and says so. */
fun copyText(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
    Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
}

/**
 * A message's text: Markdown as styled text, each fenced code block as its own box with a copy
 * button. Occurrences of [search] are marked.
 */
@Composable
internal fun MessageText(
    text: String,
    search: String,
) {
    val tint = LocalContentColor.current
    val palette = MarkdownPalette(link = tint, codeBackground = tint.copy(alpha = CODE_BG_ALPHA))
    val segments = remember(text) { MarkdownText.segments(text) }
    segments.forEach { segment ->
        if (segment.code) {
            CodeBlock(segment, search)
        } else {
            val styled = rememberMarkdown(segment.text, palette)
            val marked = remember(styled, search) { ChatMarkdown.highlight(styled, search, SearchMark) }
            Text(marked, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * What a reasoning model thought before it answered: one line to tap, the thinking itself only
 * when asked for. It is long and rarely what the reader came for.
 */
@Composable
internal fun ThoughtFold(reasoning: String) {
    var open by rememberSaveable(reasoning) { mutableStateOf(false) }
    val faint = LocalContentColor.current.copy(alpha = CODE_LABEL_ALPHA)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = faint,
                modifier = Modifier.size(20.dp),
            )
            val label = if (open) R.string.chat_thought_hide else R.string.chat_thought_show
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = faint)
        }
        if (open) Text(reasoning, style = MaterialTheme.typography.bodyMedium, color = faint)
    }
}

/** Code keeps its line breaks and scrolls sideways instead of wrapping; one tap copies all of it. */
@Composable
private fun CodeBlock(
    segment: Segment,
    search: String,
) {
    val context = LocalContext.current
    val tint = LocalContentColor.current
    val marked =
        remember(segment.text, search) { ChatMarkdown.highlight(AnnotatedString(segment.text), search, SearchMark) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = CODE_BG_ALPHA)),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                segment.language.ifBlank { stringResource(R.string.chat_code) },
                style = MaterialTheme.typography.labelMedium,
                color = tint.copy(alpha = CODE_LABEL_ALPHA),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { copyText(context, segment.text) }) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.chat_code_copy),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            marked,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

private const val CODE_BG_ALPHA = 0.12f
private const val CODE_LABEL_ALPHA = 0.7f
