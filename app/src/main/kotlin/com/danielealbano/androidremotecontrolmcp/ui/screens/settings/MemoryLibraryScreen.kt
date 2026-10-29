@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.memory.MemorySource
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MemoryEditing
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MemoryIntent
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MemoryLibraryUiState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MemoryLibraryViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MemoryRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the app remembers, one entry a row: what it says, who gets it, where it came from
 * and what it costs. The user corrects, switches off, merges or deletes; nothing here is hidden
 * from them, and nothing is sent to a model that is switched off here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryLibraryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MemoryLibraryViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(ui.notice) {
        ui.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.on(MemoryIntent.NoticeShown)
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_memory_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    val back = stringResource(R.string.settings_back)
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = back)
                }
            },
            windowInsets = WindowInsets(0),
        )
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "summary") { Summary(ui, viewModel::on) }
            items(ui.shown, key = { it.entry.id }) { row ->
                MemoryCard(row, selected = row.entry.id in ui.selected, on = viewModel::on)
            }
        }
    }
    ui.editing?.let { MemoryDialog(it, ui.contactName, viewModel::on) }
}

/** What is switched on and what it costs, the search box, and what can be done with the picked entries. */
@Composable
private fun Summary(
    ui: MemoryLibraryUiState,
    on: (MemoryIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val enabled = ui.rows.count { it.entry.enabled }
        Text(
            stringResource(R.string.memory_summary, enabled, ui.rows.size, ui.enabledTokens, ui.contactName, ui.budget),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (ui.enabledTokens > ui.budget) {
            val warn = MaterialTheme.colorScheme.error
            Text(stringResource(R.string.memory_over_budget), style = MaterialTheme.typography.bodySmall, color = warn)
        }
        OutlinedTextField(
            ui.query,
            { on(MemoryIntent.Search(it)) },
            label = { Text(stringResource(R.string.memory_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (ui.focus.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.memory_focus, ui.shown.size), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { on(MemoryIntent.ShowAll) }) { Text(stringResource(R.string.memory_show_all)) }
            }
        }
        if (ui.selected.isEmpty()) {
            val wide = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            Button(onClick = { on(MemoryIntent.Open(null)) }, modifier = wide) {
                Text(stringResource(R.string.memory_write))
            }
        } else {
            SelectionRow(ui.selected.size, on)
        }
    }
}

@Composable
private fun SelectionRow(
    count: Int,
    on: (MemoryIntent) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.memory_selected, count), style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { on(MemoryIntent.MergeSelected) }, enabled = count > 1) {
            Text(stringResource(R.string.memory_merge))
        }
        TextButton(onClick = { on(MemoryIntent.DeleteSelected) }) { Text(stringResource(R.string.memory_delete)) }
        TextButton(onClick = { on(MemoryIntent.ClearSelection) }) { Text(stringResource(R.string.chat_rename_cancel)) }
    }
}

@Composable
private fun MemoryCard(
    row: MemoryRow,
    selected: Boolean,
    on: (MemoryIntent) -> Unit,
) {
    val entry = row.entry
    val scheme = MaterialTheme.colorScheme
    val tint = if (selected) scheme.primaryContainer else scheme.surfaceContainerLow
    val open = Modifier.clickable { on(MemoryIntent.Open(entry.id)) }
    Surface(color = tint, shape = MaterialTheme.shapes.large, modifier = open) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { on(MemoryIntent.Select(entry.id)) })
            val body = Modifier.weight(1f).padding(horizontal = 4.dp)
            val cut = TextOverflow.Ellipsis
            Column(modifier = body, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = cut)
                Text(row.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = cut)
                val faint = MaterialTheme.colorScheme.onSurfaceVariant
                Text(detailOf(row), style = MaterialTheme.typography.bodySmall, color = faint)
            }
            Switch(checked = entry.enabled, onCheckedChange = { on(MemoryIntent.Enable(entry.id, it)) })
        }
    }
}

/** Who gets it, where it came from and when, and about how much it takes. */
@Composable
private fun detailOf(row: MemoryRow): String {
    val source =
        when (row.entry.source.kind) {
            MemorySource.FILE -> R.string.memory_source_file
            MemorySource.CHAT -> R.string.memory_source_chat
            else -> R.string.memory_source_manual
        }
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(row.entry.createdAt))
    return stringResource(R.string.memory_detail, row.scopeName, stringResource(source), day, row.tokens)
}

/** The whole text of a memory, as the model is given it, to write or correct; and who gets it. */
@Composable
private fun MemoryDialog(
    editing: MemoryEditing,
    contactName: String,
    on: (MemoryIntent) -> Unit,
) {
    var text by rememberSaveable(editing.id) { mutableStateOf(editing.text) }
    var global by rememberSaveable(editing.id) { mutableStateOf(editing.global) }
    val title = if (editing.id == null) R.string.memory_write else R.string.memory_edit
    AlertDialog(
        onDismissRequest = { on(MemoryIntent.Close(null, global)) },
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val tall = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 320.dp)
                OutlinedTextField(text, { text = it }, modifier = tall)
                val spaced = Arrangement.spacedBy(12.dp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = spaced) {
                    Switch(checked = global, onCheckedChange = { global = it })
                    val reach =
                        if (global) {
                            stringResource(
                                R.string.memory_for_all,
                            )
                        } else {
                            stringResource(R.string.memory_for_one, contactName)
                        }
                    Text(reach)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { on(MemoryIntent.Close(text, global)) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.chat_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { on(MemoryIntent.Close(null, global)) }) {
                Text(stringResource(R.string.chat_rename_cancel))
            }
        },
    )
}
