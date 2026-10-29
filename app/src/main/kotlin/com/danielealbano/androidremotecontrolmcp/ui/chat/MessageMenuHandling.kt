@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationEdits
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MessageAction
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.NativeChatViewModel

/** Reading a message aloud, or remembering what it says. */
private fun aboutWords(
    action: MessageMenuAction,
    id: String,
): MessageAction = if (action == MessageMenuAction.REMEMBER) MessageAction.Remember(id) else MessageAction.ReadAloud(id)

/** Carries out the long-press menu entry [action] for [item]. */
internal fun handleMenu(
    action: MessageMenuAction,
    item: ChatItem,
    state: InteractionState,
    context: Context,
    viewModel: NativeChatViewModel,
) {
    val id = item.messageId
    val words = aboutWords(action, id)
    when (action) {
        MessageMenuAction.COPY -> copyText(context, textOf(item))
        MessageMenuAction.REPLY -> viewModel.act(MessageAction.Reply(id))
        MessageMenuAction.SELECT -> state.selecting = item
        MessageMenuAction.READ, MessageMenuAction.STOP_READING, MessageMenuAction.REMEMBER -> viewModel.act(words)
        MessageMenuAction.EDIT -> state.editing = item
        MessageMenuAction.REGENERATE -> regenerate(item, state, viewModel)
        MessageMenuAction.FORWARD -> state.forwarding = item
        MessageMenuAction.OPEN -> attachmentOf(item)?.let { openAttachment(context, it) }
        MessageMenuAction.EXCLUDE -> viewModel.act(MessageAction.SetExcluded(setOf(id), excluded = true))
        MessageMenuAction.INCLUDE -> viewModel.act(MessageAction.SetExcluded(setOf(id), excluded = false))
        MessageMenuAction.MULTI_SELECT -> state.selected = setOf(id)
        MessageMenuAction.DELETE -> viewModel.act(MessageAction.Delete(id))
    }
}

/** The newest reply is asked for again at once; an earlier one first warns what would be dropped. */
private fun regenerate(
    item: ChatItem,
    state: InteractionState,
    viewModel: NativeChatViewModel,
) {
    val dropped = ConversationEdits.forRegenerate(viewModel.ui.value.messages, item.messageId)?.dropped ?: return
    if (dropped == 0) viewModel.act(MessageAction.Regenerate(item.messageId)) else state.regenerating = item
}

/** Hosts the "edit and send again" dialog and the regenerate warning. */
@Composable
internal fun MessageEditHost(
    state: InteractionState,
    viewModel: NativeChatViewModel,
) {
    state.editing?.let { item ->
        val raw = (item as? ChatItem.UserMessage)?.text.orEmpty()
        val dropped = ConversationEdits.forEdit(viewModel.ui.value.messages, item.messageId)?.dropped ?: 0
        EditMessageDialog(
            original = raw,
            dropped = dropped,
            onSend = { text ->
                state.editing = null
                viewModel.act(MessageAction.EditResend(item.messageId, text))
            },
            onDismiss = { state.editing = null },
        )
    }
    state.regenerating?.let { item ->
        val dropped = ConversationEdits.forRegenerate(viewModel.ui.value.messages, item.messageId)?.dropped ?: 0
        AlertDialog(
            onDismissRequest = { state.regenerating = null },
            title = { Text(stringResource(R.string.chat_regenerate_title)) },
            text = { Text(stringResource(R.string.chat_regenerate_warning, dropped)) },
            confirmButton = {
                TextButton(onClick = {
                    state.regenerating = null
                    viewModel.act(MessageAction.Regenerate(item.messageId))
                }) { Text(stringResource(R.string.chat_regenerate_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { state.regenerating = null }) {
                    Text(stringResource(R.string.chat_rename_cancel))
                }
            },
        )
    }
}

/**
 * The user's earlier message in a text box. Sending replaces it and everything after it, so
 * when [dropped] messages follow, the dialog says so before anything is lost.
 */
@Composable
private fun EditMessageDialog(
    original: String,
    dropped: Int,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // The box owns its text: the dialog is the only writer, so nothing can overwrite fast typing.
    var value by remember { mutableStateOf(TextFieldValue(original, TextRange(original.length))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().heightIn(max = EDIT_MAX_HEIGHT),
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
                if (dropped > 0) {
                    Text(
                        stringResource(R.string.chat_edit_drop_warning, dropped),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(value.text) }, enabled = value.text.isNotBlank()) {
                Text(stringResource(R.string.chat_edit_send))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_rename_cancel)) } },
    )
}

private val EDIT_MAX_HEIGHT = 280.dp
