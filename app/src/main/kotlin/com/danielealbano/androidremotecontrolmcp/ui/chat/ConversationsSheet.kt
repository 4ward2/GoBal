@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationHit
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationMeta
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ConversationChange
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ConversationsUiState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ConversationsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows the list of [contactName]'s conversations over the chat when the drawer asked for it,
 * and the dialogs for naming and deleting one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsHost(
    contactName: String,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<ConversationMeta?>(null) }
    var deleting by remember { mutableStateOf<ConversationMeta?>(null) }
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.exports.collect { file -> ConversationSharing.share(context, file) } }
    if (ui.open) {
        ModalBottomSheet(onDismissRequest = viewModel::hide) {
            ConversationsContent(
                ui = ui,
                contactName = contactName,
                viewModel = viewModel,
                rows = RowActions(onRename = { renaming = it }, onDelete = { deleting = it }),
            )
        }
    }
    renaming?.let { meta ->
        RenameConversationDialog(
            current = meta.title,
            onConfirm = { title ->
                renaming = null
                viewModel.change(ConversationChange.Rename(meta.id, title))
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { meta ->
        DeleteConversationDialog(
            title = meta.title.ifBlank { stringResource(R.string.conversations_untitled) },
            onConfirm = {
                deleting = null
                viewModel.change(ConversationChange.Delete(meta.id))
            },
            onDismiss = { deleting = null },
        )
    }
}

/** The two row actions that need a dialog before anything happens. */
private class RowActions(
    val onRename: (ConversationMeta) -> Unit,
    val onDelete: (ConversationMeta) -> Unit,
)

@Composable
private fun ConversationsContent(
    ui: ConversationsUiState,
    contactName: String,
    viewModel: ConversationsViewModel,
    rows: RowActions,
) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Row(modifier = Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.conversations_title, contactName),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = viewModel::startNew) {
                Icon(Icons.Default.AddComment, contentDescription = stringResource(R.string.chat_menu_new))
            }
        }
        OutlinedTextField(
            value = ui.query,
            onValueChange = viewModel::search,
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.conversations_search_hint)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (ui.query.isBlank()) {
            LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
                items(ui.conversations, key = { it.id }) { meta ->
                    ConversationRow(meta, active = meta.id == ui.activeId, viewModel = viewModel, rows = rows)
                }
            }
        } else {
            HitList(ui, onPick = viewModel::openHit)
        }
    }
}

@Composable
private fun ConversationRow(
    meta: ConversationMeta,
    active: Boolean,
    viewModel: ConversationsViewModel,
    rows: RowActions,
) {
    val tint = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Surface(onClick = { viewModel.open(meta.id) }, color = tint, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Text(
                    meta.title.ifBlank { stringResource(R.string.conversations_untitled) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = { Text(dateOf(meta.updatedAt), style = MaterialTheme.typography.labelMedium) },
            leadingContent = if (meta.pinned) ({ PinMark() }) else null,
            trailingContent = { RowMenu(meta, viewModel, rows) },
            modifier = Modifier.heightIn(min = ROW_HEIGHT),
        )
    }
}

@Composable
private fun PinMark() {
    Icon(
        Icons.Default.PushPin,
        contentDescription = stringResource(R.string.conversations_pinned),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp),
    )
}

/** "⋮" on a row: rename, pin or unpin, export, delete. */
@Composable
private fun RowMenu(
    meta: ConversationMeta,
    viewModel: ConversationsViewModel,
    rows: RowActions,
) {
    var open by remember { mutableStateOf(false) }
    val pick: (() -> Unit) -> Unit = { action ->
        open = false
        action()
    }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.conversations_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.conversations_rename)) },
                onClick = { pick { rows.onRename(meta) } },
            )
            DropdownMenuItem(
                text = {
                    Text(stringResource(if (meta.pinned) R.string.conversations_unpin else R.string.conversations_pin))
                },
                onClick = { pick { viewModel.change(ConversationChange.Pin(meta.id, !meta.pinned)) } },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.conversations_export)) },
                onClick = { pick { viewModel.export(meta) } },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.conversations_delete)) },
                onClick = { pick { rows.onDelete(meta) } },
            )
        }
    }
}

/** Search results: the words around each match, then whose conversation it is in. */
@Composable
private fun HitList(
    ui: ConversationsUiState,
    onPick: (ConversationHit) -> Unit,
) {
    if (ui.hits.isEmpty()) {
        Text(
            stringResource(R.string.conversations_no_hits),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
    val untitled = stringResource(R.string.conversations_untitled)
    LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
        items(ui.hits, key = { "${it.owner}/${it.conversationId}/${it.messageId}" }) { hit ->
            Surface(onClick = { onPick(hit) }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = {
                        Text(
                            hit.snippet,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        val who = ui.names[hit.owner].orEmpty()
                        val title = hit.conversationTitle.ifBlank { untitled }
                        val where = stringResource(R.string.conversations_hit_where, who, title)
                        Text(where, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    modifier = Modifier.heightIn(min = ROW_HEIGHT),
                )
            }
        }
    }
}

private fun dateOf(millis: Long): String =
    if (millis <= 0) "" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

private val ROW_HEIGHT = 56.dp
private val LIST_MAX_HEIGHT = 440.dp
