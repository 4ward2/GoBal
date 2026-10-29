@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.AgentAdminViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentAdminScreen(
    computerId: String,
    agentId: String,
    onBack: () -> Unit,
    onOpenTimeline: () -> Unit,
    viewModel: AgentAdminViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dialogs = remember { AgentAdminDialogState() }
    val titleEditState = remember { mutableStateOf(TitleEditState("", false, "")) }

    LaunchedEffect(computerId, agentId) { viewModel.open(computerId, agentId) }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AgentAdminTopBar(onBack, onOpenTimeline) { viewModel.refresh() }

            when {
                state.loading -> {
                    AgentAdminLoadingContent()
                }

                state.error != null -> {
                    AgentAdminErrorContent(state.error ?: "", viewModel::refresh)
                }

                state.details != null -> {
                    GoalBar(computerId, agentId)
                    AgentAdminBody(
                        state = state,
                        titleEditState = titleEditState,
                        viewModel = viewModel,
                        onShowDelete = { dialogs.showDelete.value = true },
                        onShowArchive = { dialogs.showArchive.value = true },
                    )
                }
            }
        }
    }

    AgentAdminDeleteDialog(
        visible = dialogs.showDelete.value,
        onDismiss = { dialogs.showDelete.value = false },
        onConfirm = { onBackFn ->
            viewModel.delete()
            dialogs.showDelete.value = false
            onBackFn()
        },
        onBack = onBack,
    )

    AgentAdminArchiveDialog(
        visible = dialogs.showArchive.value,
        archived = state.archived,
        onDismiss = { dialogs.showArchive.value = false },
        onConfirm = { isArchiving ->
            if (isArchiving) {
                viewModel.archive()
            } else {
                viewModel.resume()
            }
            dialogs.showArchive.value = false
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentAdminTopBar(
    onBack: () -> Unit,
    onOpenTimeline: () -> Unit,
    onRefresh: () -> Unit,
) {
    TopAppBar(
        windowInsets = WindowInsets(0),
        title = { Text(stringResource(R.string.remote_agentadmin_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.remote_common_back),
                )
            }
        },
        actions = {
            IconButton(onClick = onOpenTimeline) {
                Icon(Icons.Filled.History, stringResource(R.string.remote_timeline_open))
            }
            IconButton(onClick = onRefresh, modifier = Modifier.height(48.dp)) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.remote_agentadmin_refresh),
                )
            }
        },
    )
}

@Composable
private fun AgentAdminBody(
    state: com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.AgentAdminUiState,
    titleEditState: androidx.compose.runtime.MutableState<TitleEditState>,
    viewModel: AgentAdminViewModel,
    onShowDelete: () -> Unit,
    onShowArchive: () -> Unit,
) {
    val details = checkNotNull(state.details)
    val currentEditState = titleEditState.value
    val pickers = buildAgentPickers(state, viewModel)

    AgentAdminDetailsContent(
        titleState =
            TitleEditState(
                title = details.title,
                editing = currentEditState.editing,
                newTitle = currentEditState.newTitle,
            ),
        titleActions =
            TitleActions(
                onEditClick = {
                    titleEditState.value = currentEditState.copy(editing = true, newTitle = details.title)
                },
                onSave = {
                    viewModel.rename(currentEditState.newTitle)
                    titleEditState.value = currentEditState.copy(editing = false)
                },
                onCancel = { titleEditState.value = currentEditState.copy(editing = false) },
                onChange = { newTitle ->
                    titleEditState.value = currentEditState.copy(newTitle = newTitle)
                },
            ),
        labels =
            AgentInfoLabels(
                provider = details.provider,
                status = details.status,
                cwd = details.cwd,
            ),
        pickers =
            AgentPickers(
                mode = pickers.mode,
                model = pickers.model,
            ),
        lifecycle = AgentLifecycleActions(state.archived, onArchive = onShowArchive, onDelete = onShowDelete),
    )
}

@Composable
private fun buildAgentPickers(
    state: com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.AgentAdminUiState,
    viewModel: AgentAdminViewModel,
): AgentPickers {
    val modeSpec =
        if (state.modes.isNotEmpty()) {
            DropdownPickerSpec(
                label = stringResource(R.string.remote_agentadmin_mode),
                selectedValue = state.details?.modeId ?: "",
                options = state.modes,
                onOptionSelected = { viewModel.setMode(it) },
                getText = { it.label },
                getValue = { it.id },
            )
        } else {
            null
        }

    val modelSpec =
        if (state.models.isNotEmpty()) {
            DropdownPickerSpec(
                label = stringResource(R.string.remote_agentadmin_model),
                selectedValue = state.details?.model ?: "",
                options = state.models,
                onOptionSelected = { viewModel.setModel(it) },
                getText = { it.label },
                getValue = { it.id },
            )
        } else {
            null
        }

    return AgentPickers(mode = modeSpec, model = modelSpec)
}
