@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.WorkspaceInfo
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.WorkspacesUiState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.WorkspacesViewModel

/** The computer's workspaces (projects and worktrees), each with its files, git, terminals and scripts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspacesScreen(
    computerId: String,
    onBack: () -> Unit,
    onOpen: (WorkspacePage, String) -> Unit,
    viewModel: WorkspacesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var archiving by remember { mutableStateOf<WorkspaceInfo?>(null) }
    LaunchedEffect(computerId) { viewModel.open(computerId) }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        TopAppBar(
            title = { Text(stringResource(R.string.remote_workspaces_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.remote_workspaces_back))
                }
            },
            windowInsets = WindowInsets(0),
        )
        val actions =
            WorkspaceActions(
                onOpen = { page, workspace -> onOpen(page, workspace.workspaceDirectory) },
                onRun = { workspace, script -> viewModel.startScript(computerId, workspace.id, script.scriptName) },
                onArchive = { archiving = it },
            )
        WorkspaceList(state, actions)
    }
    archiving?.let { workspace ->
        ArchiveDialog(workspace, onDismiss = { archiving = null }) {
            archiving = null
            viewModel.archive(computerId, workspace.id)
        }
    }
}

@Composable
private fun WorkspaceList(
    state: WorkspacesUiState,
    actions: WorkspaceActions,
) {
    val error = state.error
    when {
        state.isLoading && state.workspaces.isEmpty() -> {
            Centered { CircularProgressIndicator() }
        }

        !error.isNullOrEmpty() -> {
            Centered {
                Text(stringResource(R.string.remote_workspaces_error, error), color = MaterialTheme.colorScheme.error)
            }
        }

        state.workspaces.isEmpty() -> {
            Centered { Text(stringResource(R.string.remote_workspaces_empty)) }
        }

        else -> {
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.workspaces, key = { it.id }) { workspace -> WorkspaceCard(workspace, actions) }
            }
        }
    }
}

/** Archiving takes the workspace's agents and terminals with it, and a Paseo worktree's directory. */
@Composable
private fun ArchiveDialog(
    workspace: WorkspaceInfo,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_workspaces_archive_ask, workspace.title ?: workspace.name)) },
        text = { Text(stringResource(R.string.remote_workspaces_archive_hint)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.remote_workspaces_archive), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_common_cancel)) } },
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}
