@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.chat.Attachment
import com.danielealbano.androidremotecontrolmcp.services.chat.ForwardTarget
import com.danielealbano.androidremotecontrolmcp.services.chat.MarkdownText
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MessageAction
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.NativeChatViewModel
import java.io.File

/** The text a row carries, for copy / select / share: what is shown, not the Markdown behind it. */
fun textOf(item: ChatItem): String =
    when (item) {
        is ChatItem.UserMessage -> MarkdownText.plain(item.text)
        is ChatItem.AssistantMessage -> MarkdownText.plain(item.text)
        is ChatItem.PhoneTask -> item.request
        is ChatItem.SkillReview -> item.text
    }

fun attachmentOf(item: ChatItem): Attachment? =
    when (item) {
        is ChatItem.UserMessage -> item.attachment
        is ChatItem.AssistantMessage -> item.attachment
        is ChatItem.PhoneTask, is ChatItem.SkillReview -> null
    }

/** Row callbacks: long-press opens the menu, quotes jump, pictures open, documents hand off. */
fun bubbleActions(
    state: InteractionState,
    context: Context,
    viewModel: NativeChatViewModel,
    onJumpTo: (String) -> Unit,
): BubbleActions =
    BubbleActions(
        // While several messages are being picked, touching a row picks it instead.
        onLongPress = { if (state.selected != null) state.toggle(it.messageId) else state.menuFor = it },
        onQuoteTap = onJumpTo,
        onImageTap = { state.viewing = it },
        onFileTap = { openAttachment(context, it) },
        onTap = { state.toggle(it.messageId) },
        task =
            TaskActions(
                onStop = { viewModel.act(MessageAction.StopTask) },
                onTakeOver = { viewModel.act(MessageAction.TakeOverTask) },
            ),
    )

/** Hosts the long-press menu, forward sheet, partial-quote dialog and image viewer. */
@Composable
fun MessageInteractionHost(
    state: InteractionState,
    targets: List<ForwardTarget>,
    viewModel: NativeChatViewModel,
) {
    val context = LocalContext.current
    state.menuFor?.let { item ->
        val ui = viewModel.ui.value
        val excluded = ui.messages.any { it.id == item.messageId && it.excluded }
        MessageActionsSheet(
            item = item,
            marks = MessageMarks(excluded, reading = ui.readingId == item.messageId),
            onPick = { action ->
                state.menuFor = null
                handleMenu(action, item, state, context, viewModel)
            },
            onDismiss = { state.menuFor = null },
        )
    }
    state.forwarding?.let { item ->
        ForwardSheet(
            targets = targets,
            onPick = { target ->
                state.forwarding = null
                viewModel.act(MessageAction.Forward(item.messageId, target))
                toast(context, context.getString(R.string.chat_forwarded, target.name))
            },
            onShare = {
                state.forwarding = null
                shareItem(context, item)
            },
            onDismiss = { state.forwarding = null },
        )
    }
    state.selecting?.let { item ->
        SelectTextDialog(
            text = textOf(item),
            onQuote = { excerpt ->
                state.selecting = null
                viewModel.act(MessageAction.Reply(item.messageId, excerpt))
            },
            onDismiss = { state.selecting = null },
        )
    }
    state.viewing?.let { ImageViewerDialog(it, onDismiss = { state.viewing = null }) }
    MessageEditHost(state, viewModel)
}

private fun toast(
    context: Context,
    text: String,
) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

private fun contentUri(
    context: Context,
    attachment: Attachment,
) = FileProvider.getUriForFile(context, "${context.packageName}.files", File(attachment.path))

/** Starts a chooser for [intent]; failures (no app can handle it) become a toast. */
private fun launch(
    context: Context,
    intent: Intent,
    title: String?,
) {
    val chooser = Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }.onFailure { toast(context, it.message ?: "无法打开") }
}

/** Opens a document attachment in whatever app handles its type. */
fun openAttachment(
    context: Context,
    attachment: Attachment,
) {
    val intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri(context, attachment), attachment.mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    launch(context, intent, attachment.name)
}

/** The system share sheet with the message text and, when there is one, the attachment file. */
fun shareItem(
    context: Context,
    item: ChatItem,
) {
    val attachment = attachmentOf(item)
    val text = textOf(item)
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            if (attachment != null) {
                type = attachment.mime.ifBlank { if (attachment.isImage) "image/*" else "*/*" }
                putExtra(Intent.EXTRA_STREAM, contentUri(context, attachment))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
            if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
        }
    launch(context, intent, null)
}
