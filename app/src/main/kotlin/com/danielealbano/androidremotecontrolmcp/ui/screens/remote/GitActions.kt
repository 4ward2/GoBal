@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.BranchInfo
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.WorktreeInfo
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.GitUiState

data class GitActions(
    val onSearchChange: (String) -> Unit,
    val onSwitchBranch: (String) -> Unit,
    val onPush: () -> Unit,
    val onCreateWorktree: () -> Unit,
    val onDeleteWorktree: (String) -> Unit,
    val onDismissPushResult: () -> Unit,
)

@Composable
internal fun CurrentBranchCard(
    branchName: String,
    pending: String,
    onPush: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ).padding(16.dp),
    ) {
        Text(
            stringResource(R.string.remote_git_current_branch),
            style = MaterialTheme.typography.labelSmall,
        )
        androidx.compose.foundation.layout
            .Spacer(modifier = Modifier.height(4.dp))
        Text(
            branchName.ifEmpty { stringResource(R.string.remote_git_unknown) },
            style = MaterialTheme.typography.headlineSmall,
        )
        androidx.compose.foundation.layout
            .Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (pending.isNotEmpty()) {
                Text(
                    pending,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                )
            }
            OutlinedButton(onClick = onPush) {
                Icon(
                    Icons.Filled.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(stringResource(R.string.remote_git_push))
            }
        }
    }
}

@Composable
internal fun BranchSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = {
            Text(stringResource(R.string.remote_git_search_branches))
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun BranchListItem(
    branch: BranchInfo,
    onSelect: () -> Unit,
) {
    OutlinedButton(
        onClick = onSelect,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    branch.name,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (branch.isCurrent) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription =
                            stringResource(R.string.remote_git_current_branch),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (branch.isRemote) {
                Text(
                    stringResource(R.string.remote_git_remote),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun WorktreeListItem(
    worktree: WorktreeInfo,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                worktree.path.substringAfterLast("/"),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!worktree.branch.isNullOrEmpty()) {
                Text(
                    worktree.branch,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.padding(0.dp),
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.remote_git_delete_worktree),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
internal fun PushResultBar(
    result: String?,
    onDismiss: () -> Unit,
) {
    if (result != null) {
        androidx.compose.foundation.layout.Box(
            modifier =
                Modifier
                    .padding(16.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                    ).padding(16.dp)
                    .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(result)
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.remote_common_dismiss))
                }
            }
        }
    }
}

@Composable
internal fun GitLoadingState() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun GitErrorState(
    error: String,
    onDismiss: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onDismiss) {
            Text(stringResource(R.string.remote_common_dismiss))
        }
    }
}

@Composable
internal fun GitContentState(
    state: GitUiState,
    searchQuery: String,
    actions: GitActions,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            CurrentBranchCard(
                branchName = state.currentBranch,
                pending = state.pending,
                onPush = actions.onPush,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            BranchSearchField(
                query = searchQuery,
                onQueryChange = actions.onSearchChange,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        gitBranchesSection(
            branches = state.branches,
            onSwitchBranch = actions.onSwitchBranch,
        )

        if (state.worktrees.isNotEmpty()) {
            gitWorktreesSection(
                worktrees = state.worktrees,
                onCreateWorktree = actions.onCreateWorktree,
                onDeleteWorktree = actions.onDeleteWorktree,
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    PushResultBar(result = state.pushResult, onDismiss = actions.onDismissPushResult)
}

private fun LazyListScope.gitBranchesSection(
    branches: List<BranchInfo>,
    onSwitchBranch: (String) -> Unit,
) {
    item {
        Text(
            stringResource(R.string.remote_git_branches),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    items(branches, key = { it.name }) { branch ->
        BranchListItem(
            branch = branch,
            onSelect = { onSwitchBranch(branch.name) },
        )
    }
}

private fun LazyListScope.gitWorktreesSection(
    worktrees: List<WorktreeInfo>,
    onCreateWorktree: () -> Unit,
    onDeleteWorktree: (String) -> Unit,
) {
    item {
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.remote_git_worktrees),
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = onCreateWorktree, modifier = Modifier.padding(0.dp)) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.remote_git_create_worktree),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    items(worktrees, key = { it.path }) { worktree ->
        WorktreeListItem(
            worktree = worktree,
            onDelete = { onDeleteWorktree(worktree.path) },
        )
    }
}
