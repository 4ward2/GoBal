@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.remote.PendingPermission
import com.danielealbano.androidremotecontrolmcp.services.remote.PermissionReply
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteChoice

private const val DETAIL_LINES = 6
private const val REASON_LINES = 3

/**
 * What an agent on a computer is waiting to be allowed to do, with every answer it offers. Nothing is
 * allowed by default and nothing answers for the user: the agent waits until one is tapped.
 */
@Composable
fun RemotePermissionCards(
    pending: List<PendingPermission>,
    onAnswer: (PendingPermission, PermissionReply) -> Unit,
) {
    val wide = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    Column(modifier = wide, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pending.forEach { request -> RemotePermissionCard(request) { onAnswer(request, it) } }
    }
}

@Composable
private fun RemotePermissionCard(
    pending: PendingPermission,
    onReply: (PermissionReply) -> Unit,
) {
    val asked = pending.permission
    var explaining by remember(asked.id) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.remote_permission_title), style = MaterialTheme.typography.labelLarge)
            Text(asked.title.ifBlank { asked.name }, style = MaterialTheme.typography.titleMedium)
            if (asked.detail.isNotBlank()) {
                Text(
                    asked.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = DETAIL_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ChoiceButtons(asked.choices, onReply)
            TextButton(onClick = { explaining = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.remote_permission_deny_explain))
            }
        }
    }
    if (explaining) {
        ReasonDialog(onDismiss = { explaining = false }) { reason ->
            explaining = false
            onReply(PermissionReply(allow = false, message = reason))
        }
    }
}

/** The agent's own answers stacked full width (labels can be long); plain allow and deny when it offers none. */
@Composable
private fun ChoiceButtons(
    choices: List<RemoteChoice>,
    onReply: (PermissionReply) -> Unit,
) {
    val tall = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    if (choices.isEmpty()) {
        OutlinedButton(onClick = { onReply(PermissionReply(allow = false)) }, modifier = tall) {
            Text(stringResource(R.string.remote_permission_deny))
        }
        Button(onClick = { onReply(PermissionReply(allow = true)) }, modifier = tall) {
            Text(stringResource(R.string.remote_permission_allow))
        }
        return
    }
    choices.forEach { choice ->
        val reply = PermissionReply(allow = choice.allow, choiceId = choice.id)
        if (choice.allow) {
            Button(onClick = { onReply(reply) }, modifier = tall) { Text(choice.label) }
        } else {
            OutlinedButton(onClick = { onReply(reply) }, modifier = tall) { Text(choice.label) }
        }
    }
}

/** A refusal with a word for the agent, so it knows what to do instead. */
@Composable
private fun ReasonDialog(
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_permission_deny_explain)) },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                placeholder = { Text(stringResource(R.string.remote_permission_reason_hint)) },
                minLines = REASON_LINES,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSend(reason.trim()) }) { Text(stringResource(R.string.remote_permission_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_common_cancel)) }
        },
    )
}
