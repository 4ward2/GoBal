@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.chat.OutlineEntry

/**
 * The user's questions as a numbered list; tapping one jumps to it. A word typed at the top
 * narrows the list, which is what makes a long conversation navigable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineSheet(
    entries: List<OutlineEntry>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val shown =
        remember(entries, filter) {
            val wanted = filter.trim()
            if (wanted.isEmpty()) entries else entries.filter { it.title.contains(wanted, ignoreCase = true) }
        }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.chat_outline),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (entries.size > FILTER_FROM) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_outline_filter)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.chat_outline_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
            LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
                items(shown, key = { it.id }) { entry -> OutlineRow(entry, onPick) }
            }
        }
    }
}

@Composable
private fun OutlineRow(
    entry: OutlineEntry,
    onPick: (String) -> Unit,
) {
    Surface(onClick = { onPick(entry.id) }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = {
                Text(
                    "${entry.number}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            headlineContent = {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.heightIn(min = ROW_HEIGHT),
        )
    }
}

private val ROW_HEIGHT = 52.dp
private val LIST_MAX_HEIGHT = 440.dp

/** A short list needs no filter box. */
private const val FILTER_FROM = 6
