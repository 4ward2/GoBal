@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.GitUiState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.GitViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(
    computerId: String,
    workspacePath: String,
    onBack: () -> Unit,
    viewModel: GitViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dialogs = remember { GitDialogState() }
    val searchQuery = remember { mutableStateOf("") }

    LaunchedEffect(computerId, workspacePath) {
        viewModel.open(computerId, workspacePath)
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            GitTopBar(onBack) { viewModel.refresh() }

            GitContentBody(
                state = state,
                searchQuery = searchQuery,
                dialogs = dialogs,
                viewModel = viewModel,
            )
        }
    }

    GitDialogs(dialogs = dialogs, viewModel = viewModel)
}

@Composable
private fun GitContentBody(
    state: GitUiState,
    searchQuery: androidx.compose.runtime.MutableState<String>,
    dialogs: GitDialogState,
    viewModel: GitViewModel,
) {
    when {
        state.isLoading -> {
            GitLoadingState()
        }

        state.error != null -> {
            GitErrorState(error = state.error.orEmpty(), onDismiss = { viewModel.dismiss() })
        }

        else -> {
            GitContentState(
                state = state,
                searchQuery = searchQuery.value,
                actions =
                    GitActions(
                        onSearchChange = { query ->
                            searchQuery.value = query
                            if (query.isNotEmpty()) {
                                viewModel.searchBranches(query)
                            } else {
                                viewModel.refresh()
                            }
                        },
                        onSwitchBranch = { branch ->
                            dialogs.selectedBranch.value = branch
                            dialogs.showSwitchBranch.value = true
                        },
                        onPush = { dialogs.confirmingPush.value = true },
                        onCreateWorktree = { dialogs.showCreateWorktree.value = true },
                        onDeleteWorktree = { dialogs.archiving.value = it },
                        onDismissPushResult = { viewModel.dismiss() },
                    ),
            )
        }
    }
}

@Composable
private fun GitDialogs(
    dialogs: GitDialogState,
    viewModel: GitViewModel,
) {
    SwitchBranchDialog(
        visible = dialogs.showSwitchBranch.value,
        selectedBranch = dialogs.selectedBranch.value,
        onDismiss = {
            dialogs.showSwitchBranch.value = false
            dialogs.selectedBranch.value = ""
        },
        onConfirm = {
            viewModel.switchBranch(dialogs.selectedBranch.value)
            dialogs.showSwitchBranch.value = false
            dialogs.selectedBranch.value = ""
        },
    )

    if (dialogs.confirmingPush.value) {
        ConfirmDialog(
            title = stringResource(R.string.remote_git_push_ask),
            text = stringResource(R.string.remote_git_push_hint),
            onDismiss = { dialogs.confirmingPush.value = false },
        ) {
            dialogs.confirmingPush.value = false
            viewModel.push()
        }
    }
    dialogs.archiving.value?.let { path ->
        ConfirmDialog(
            title = stringResource(R.string.remote_git_archive_worktree_ask),
            text = stringResource(R.string.remote_git_archive_worktree_hint, path),
            onDismiss = { dialogs.archiving.value = null },
        ) {
            dialogs.archiving.value = null
            viewModel.archiveWorktree(path)
        }
    }

    CreateWorktreeDialog(
        visible = dialogs.showCreateWorktree.value,
        branchName = dialogs.newWorktreeBranch.value,
        onBranchChange = { dialogs.newWorktreeBranch.value = it },
        onDismiss = { dialogs.showCreateWorktree.value = false },
        onConfirm = {
            if (dialogs.newWorktreeBranch.value.isNotEmpty()) {
                viewModel.createWorktree(dialogs.newWorktreeBranch.value)
                dialogs.showCreateWorktree.value = false
                dialogs.newWorktreeBranch.value = ""
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitTopBar(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.remote_git_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.remote_common_back),
                )
            }
        },
        actions = {
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.remote_git_refresh),
                )
            }
        },
        windowInsets = WindowInsets(0),
    )
}
