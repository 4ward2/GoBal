@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
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
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationCatalog

/** A name for a conversation; left empty, it goes back to being called after its first question. */
@Composable
fun RenameConversationDialog(
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(current.length))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conversations_rename)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.text.length <= ConversationCatalog.MAX_TITLE) value = it },
                singleLine = true,
                supportingText = { Text(stringResource(R.string.conversations_rename_hint)) },
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }) { Text(stringResource(R.string.chat_rename_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_rename_cancel)) } },
    )
}

/** Deleting a conversation cannot be undone, so it is always asked first. */
@Composable
fun DeleteConversationDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conversations_delete_title)) },
        text = { Text(stringResource(R.string.conversations_delete_text, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.conversations_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_rename_cancel)) } },
    )
}
