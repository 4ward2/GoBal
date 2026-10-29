@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MemoryDraft

/** The way to give a contact something to remember without a file: write it down. */
@Composable
internal fun WriteMemoryButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(Icons.Default.Edit, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.contact_memory_write))
    }
}

/**
 * Shows what a memory file holds, as the model will read it, and lets the user correct it (OCR
 * gets words wrong) or write a new note. [onDone] gets the text to keep, or null when cancelled.
 */
@Composable
internal fun MemoryEditDialog(
    draft: MemoryDraft,
    onDone: (String?) -> Unit,
) {
    var text by rememberSaveable(draft.id) { mutableStateOf(draft.text) }
    val title = draft.name.ifEmpty { stringResource(R.string.contact_memory_write) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title, maxLines = 1) },
        text = {
            Column {
                Text(
                    stringResource(R.string.contact_memory_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.chat_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.chat_rename_cancel)) }
        },
    )
}
