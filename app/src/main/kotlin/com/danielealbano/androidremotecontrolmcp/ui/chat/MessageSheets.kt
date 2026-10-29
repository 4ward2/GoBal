@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shortcut
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.AppTier
import com.danielealbano.androidremotecontrolmcp.services.chat.Attachment
import com.danielealbano.androidremotecontrolmcp.services.chat.ForwardTarget
import com.danielealbano.androidremotecontrolmcp.ui.LocalTier
import com.danielealbano.androidremotecontrolmcp.ui.components.AssistantAvatar
import java.io.File

/** WeChat / Telegram style long-press sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    item: ChatItem,
    marks: MessageMarks,
    onPick: (MessageMenuAction) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            val simple = LocalTier.current == AppTier.SIMPLE
            menuFor(item, marks.excluded, marks.reading, simple).forEach { action ->
                val (icon, label) = iconAndLabel(action)
                Surface(onClick = { onPick(action) }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(stringResource(label), style = MaterialTheme.typography.titleMedium) },
                        leadingContent = { Icon(icon, contentDescription = null) },
                        modifier = Modifier.heightIn(min = ROW_HEIGHT),
                    )
                }
            }
        }
    }
}

private fun iconAndLabel(action: MessageMenuAction): Pair<ImageVector, Int> =
    when (action) {
        MessageMenuAction.COPY -> Icons.Default.ContentCopy to R.string.chat_action_copy
        MessageMenuAction.REPLY -> Icons.AutoMirrored.Filled.Reply to R.string.chat_action_reply
        MessageMenuAction.SELECT -> Icons.Default.FormatQuote to R.string.chat_action_select
        MessageMenuAction.READ, MessageMenuAction.STOP_READING, MessageMenuAction.REMEMBER -> voiceOrMemory(action)
        MessageMenuAction.EDIT -> Icons.Default.Edit to R.string.chat_action_edit
        MessageMenuAction.REGENERATE -> Icons.Default.Refresh to R.string.chat_action_regenerate
        MessageMenuAction.FORWARD -> Icons.Default.Shortcut to R.string.chat_action_forward
        MessageMenuAction.EXCLUDE -> Icons.Default.VisibilityOff to R.string.chat_action_exclude
        MessageMenuAction.INCLUDE -> Icons.Default.Visibility to R.string.chat_action_include
        MessageMenuAction.MULTI_SELECT -> Icons.Default.Checklist to R.string.chat_action_multi
        MessageMenuAction.OPEN -> Icons.Default.OpenInNew to R.string.chat_action_open
        MessageMenuAction.DELETE -> Icons.Default.Delete to R.string.chat_action_delete
    }

/** The entries about a message's words rather than the message: reading it aloud, remembering it. */
private fun voiceOrMemory(action: MessageMenuAction): Pair<ImageVector, Int> =
    when (action) {
        MessageMenuAction.READ -> Icons.AutoMirrored.Filled.VolumeUp to R.string.chat_action_read
        MessageMenuAction.STOP_READING -> Icons.Default.StopCircle to R.string.chat_action_stop_reading
        else -> Icons.Default.Psychology to R.string.chat_action_remember
    }

/** Pick another conversation to forward into, or hand the message to the system share sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardSheet(
    targets: List<ForwardTarget>,
    onPick: (ForwardTarget) -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.chat_forward_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            targets.forEach { target ->
                Surface(onClick = { onPick(target) }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(target.name, style = MaterialTheme.typography.titleMedium) },
                        leadingContent = { AssistantAvatar(name = target.name, size = 36.dp) },
                        modifier = Modifier.heightIn(min = ROW_HEIGHT),
                    )
                }
            }
            Surface(onClick = onShare, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.chat_forward_share), style = MaterialTheme.typography.titleMedium)
                    },
                    leadingContent = { Icon(Icons.Default.Share, contentDescription = null) },
                    modifier = Modifier.heightIn(min = ROW_HEIGHT),
                )
            }
        }
    }
}

/**
 * Select part of a message to quote. A read-only text field is used because Compose exposes the
 * selection of a text field, not of a `SelectionContainer`.
 */
@Composable
fun SelectTextDialog(
    text: String,
    onQuote: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(0, 0))) }
    val selected = value.text.substring(value.selection.min, value.selection.max).trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_select_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                readOnly = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = SELECT_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                textStyle = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = {
            TextButton(onClick = { onQuote(selected) }, enabled = selected.isNotEmpty()) {
                Text(stringResource(R.string.chat_select_quote))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_rename_cancel)) } },
    )
}

/** A picture from the chat, full screen; tap anywhere to close. */
@Composable
fun ImageViewerDialog(
    attachment: Attachment,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(onClick = onDismiss, color = Color.Black, modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = File(attachment.path),
                contentDescription = attachment.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private val ROW_HEIGHT = 56.dp
private val SELECT_MAX_HEIGHT = 360.dp
