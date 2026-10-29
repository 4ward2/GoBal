@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.chat.Attachment
import com.danielealbano.androidremotecontrolmcp.services.chat.QuotedMessage
import java.io.File

/**
 * One conversation row, dressed as [look] says: flashing after a jump, search hits marked, faded
 * with a label when left out of the model's context, a tick in front while picking messages.
 */
@Composable
fun ChatItemRow(
    item: ChatItem,
    speaker: Speaker,
    actions: BubbleActions,
    look: RowLook = RowLook(),
) {
    Column(Modifier.fillMaxWidth().alpha(if (look.excluded) EXCLUDED_ALPHA else 1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            look.selected?.let { SelectMark(it) }
            Box(Modifier.weight(1f)) { RowContent(item, speaker, actions, look) }
        }
        if (look.excluded) {
            Text(
                stringResource(R.string.chat_excluded_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (item is ChatItem.UserMessage) TextAlign.End else TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(start = EXCLUDED_LABEL_INSET, top = 2.dp),
            )
        }
    }
}

@Composable
private fun RowContent(
    item: ChatItem,
    speaker: Speaker,
    actions: BubbleActions,
    look: RowLook,
) {
    // A skill card is drawn by the chat screen with the live skill (see SkillReviewCard); here it
    // falls back to a plain bubble.
    val plain = (item as? ChatItem.SkillReview)?.let { ChatItem.AssistantMessage(it.key, it.text) }
    when (item) {
        is ChatItem.UserMessage -> UserBubble(item, actions, look)
        is ChatItem.AssistantMessage -> AssistantRow(speaker) { AnswerWithNote(item, actions, look) }
        is ChatItem.PhoneTask -> AssistantRow(speaker) { PhoneTaskCard(item, actions, look.task) }
        is ChatItem.SkillReview -> AssistantRow(speaker) { plain?.let { AssistantBubble(it, actions, look) } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    item: ChatItem.UserMessage,
    actions: BubbleActions,
    look: RowLook,
) {
    val scheme = MaterialTheme.colorScheme
    val color by animateColorAsState(if (look.highlighted) scheme.tertiary else scheme.primary, label = "flash")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(BubbleCorner, BubbleCorner, BubbleTail, BubbleCorner),
            color = color,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier =
                Modifier
                    .widthIn(max = MaxBubbleWidth)
                    .combinedClickable(onClick = { actions.onTap(item) }, onLongClick = { actions.onLongPress(item) }),
        ) {
            BubbleBody(BodyParts(item.text, item.quote, item.forwardedFrom, item.attachment), actions, look.search)
        }
    }
}

/** An answer, and under it which memories it was given. */
@Composable
private fun AnswerWithNote(
    item: ChatItem.AssistantMessage,
    actions: BubbleActions,
    look: RowLook,
) {
    Column {
        AssistantBubble(item, actions, look)
        item.memory?.let { MemoryLine(it) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubble(
    item: ChatItem.AssistantMessage,
    actions: BubbleActions,
    look: RowLook,
) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val flash = MaterialTheme.colorScheme.tertiaryContainer
    val color by animateColorAsState(if (look.highlighted) flash else base, label = "flash")
    Surface(
        shape = RoundedCornerShape(BubbleCorner, BubbleCorner, BubbleCorner, BubbleTail),
        color = color,
        modifier =
            Modifier
                .widthIn(max = MaxBubbleWidth)
                .combinedClickable(onClick = { actions.onTap(item) }, onLongClick = { actions.onLongPress(item) }),
    ) {
        val parts = BodyParts(item.text, item.quote, item.forwardedFrom, item.attachment, item.reasoning)
        BubbleBody(parts, actions, look.search)
    }
}

/** What a bubble holds, top to bottom. */
private class BodyParts(
    val text: String,
    val quote: QuotedMessage?,
    val forwardedFrom: String?,
    val attachment: Attachment?,
    val reasoning: String = "",
)

/** Forwarded label, quote block, attachment, then the text: the order messengers use. */
@Composable
private fun BubbleBody(
    parts: BodyParts,
    actions: BubbleActions,
    search: String,
) {
    Column(
        modifier = Modifier.padding(BODY_PADDING_H, BODY_PADDING_V),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        parts.forwardedFrom?.let {
            Text(
                stringResource(R.string.chat_forwarded_from, it),
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(alpha = LABEL_ALPHA),
            )
        }
        parts.quote?.let { QuoteBlock(it, onClick = { actions.onQuoteTap(it.id) }) }
        parts.attachment?.let { AttachmentBody(it, actions) }
        if (parts.reasoning.isNotBlank()) ThoughtFold(parts.reasoning)
        if (parts.text.isNotBlank()) MessageText(parts.text, search)
    }
}

/** The quoted message: a coloured bar, who said it, and its excerpt. Tapping jumps to it. */
@Composable
fun QuoteBlock(
    quote: QuotedMessage,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tint = LocalContentColor.current
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = QUOTE_BG_ALPHA))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(end = 10.dp)
                .heightIn(min = 36.dp),
    ) {
        Box(Modifier.width(3.dp).heightIn(min = 36.dp).background(tint.copy(alpha = QUOTE_BAR_ALPHA)))
        Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
            Text(quote.speaker, style = MaterialTheme.typography.labelMedium, color = tint.copy(alpha = LABEL_ALPHA))
            Text(
                quote.excerpt,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = tint.copy(alpha = QUOTE_TEXT_ALPHA),
            )
        }
    }
}

@Composable
private fun AttachmentBody(
    attachment: Attachment,
    actions: BubbleActions,
) {
    if (attachment.isImage) {
        AsyncImage(
            model = File(attachment.path),
            contentDescription = stringResource(R.string.chat_image),
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .widthIn(max = IMAGE_MAX_WIDTH)
                    .heightIn(max = IMAGE_MAX_HEIGHT)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { actions.onImageTap(attachment) },
        )
    } else {
        FileChip(attachment, onClick = { actions.onFileTap(attachment) })
    }
}

/** A document in the chat: icon, name and how much text was read out of it. */
@Composable
fun FileChip(
    attachment: Attachment,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tint = LocalContentColor.current
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = QUOTE_BG_ALPHA))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                attachment.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.contact_memory_chars, attachment.text.length),
                style = MaterialTheme.typography.labelSmall,
                color = tint.copy(alpha = LABEL_ALPHA),
            )
        }
    }
}

private val BODY_PADDING_H = 14.dp
private val BODY_PADDING_V = 10.dp
private val IMAGE_MAX_WIDTH = 240.dp
private val IMAGE_MAX_HEIGHT = 320.dp
private const val EXCLUDED_ALPHA = 0.5f
private val EXCLUDED_LABEL_INSET = 36.dp
private const val LABEL_ALPHA = 0.7f
private const val QUOTE_TEXT_ALPHA = 0.85f
private const val QUOTE_BG_ALPHA = 0.12f
private const val QUOTE_BAR_ALPHA = 0.6f
