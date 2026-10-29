@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteMode
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteModel

data class TitleEditState(
    val title: String,
    val editing: Boolean,
    val newTitle: String,
)

data class DropdownPickerSpec<T>(
    val label: String,
    val selectedValue: String,
    val options: List<T>,
    val onOptionSelected: (String) -> Unit,
    val getText: (T) -> String = { it.toString() },
    val getValue: (T) -> String = { it.toString() },
)

/** Archive or delete one agent; [archived] decides which of the two the card offers. */
data class AgentLifecycleActions(
    val archived: Boolean,
    val onArchive: () -> Unit,
    val onDelete: () -> Unit,
)

data class TitleActions(
    val onEditClick: () -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
    val onChange: (String) -> Unit,
)

data class AgentInfoLabels(
    val provider: String,
    val status: String,
    val cwd: String,
)

data class AgentPickers(
    val mode: DropdownPickerSpec<RemoteMode>?,
    val model: DropdownPickerSpec<RemoteModel>?,
)

@Composable
fun AgentAdminLoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun AgentAdminErrorContent(
    error: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.remote_agentadmin_retry)) }
    }
}

@Composable
fun AgentAdminDetailsContent(
    titleState: TitleEditState,
    titleActions: TitleActions,
    labels: AgentInfoLabels,
    pickers: AgentPickers,
    lifecycle: AgentLifecycleActions,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TitleSection(state = titleState, actions = titleActions)
        InfoRow(
            label = stringResource(R.string.remote_agentadmin_provider),
            value = labels.provider,
        )
        InfoRow(
            label = stringResource(R.string.remote_agentadmin_status),
            value = labels.status,
        )
        InfoRow(label = stringResource(R.string.remote_agentadmin_cwd), value = labels.cwd)

        if (pickers.mode != null) {
            DropdownSection(spec = pickers.mode)
        }

        if (pickers.model != null) {
            DropdownSection(spec = pickers.model)
        }

        Spacer(modifier = Modifier.height(8.dp))

        AgentAdminActionButtons(
            archived = lifecycle.archived,
            onArchiveClick = lifecycle.onArchive,
            onDeleteClick = lifecycle.onDelete,
        )
    }
}

@Composable
private fun TitleSection(
    state: TitleEditState,
    actions: TitleActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.editing) {
            OutlinedTextField(
                value = state.newTitle,
                onValueChange = actions.onChange,
                label = { Text(stringResource(R.string.remote_agentadmin_title_label)) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(onClick = actions.onSave, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.remote_agentadmin_save))
                }
                OutlinedButton(onClick = actions.onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.remote_agentadmin_cancel))
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = actions.onEditClick, modifier = Modifier.height(48.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription =
                            stringResource(R.string.remote_agentadmin_edit_title),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun <T> DropdownSection(spec: DropdownPickerSpec<T>) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(spec.label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(
                    spec.selectedValue.ifEmpty {
                        stringResource(R.string.remote_agentadmin_select_placeholder)
                    },
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(),
            ) {
                spec.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(spec.getText(option)) },
                        onClick = {
                            spec.onOptionSelected(spec.getValue(option))
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentAdminActionButtons(
    archived: Boolean,
    onArchiveClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val archiveButton =
            if (archived) {
                stringResource(R.string.remote_agentadmin_resume)
            } else {
                stringResource(R.string.remote_agentadmin_archive)
            }
        val archiveIcon =
            if (archived) {
                Icons.Filled.Unarchive
            } else {
                Icons.Filled.Archive
            }

        Button(
            onClick = onArchiveClick,
            modifier = Modifier.weight(1f).height(48.dp),
        ) {
            Icon(archiveIcon, contentDescription = archiveButton, modifier = Modifier.padding(end = 8.dp))
            Text(archiveButton)
        }

        OutlinedButton(
            onClick = onDeleteClick,
            modifier = Modifier.weight(1f).height(48.dp),
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.remote_agentadmin_delete),
                modifier = Modifier.padding(end = 8.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(R.string.remote_agentadmin_delete),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
