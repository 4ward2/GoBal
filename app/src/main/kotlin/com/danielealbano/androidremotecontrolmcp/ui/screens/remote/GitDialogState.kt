@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R

@Stable
class GitDialogState {
    val showSwitchBranch = mutableStateOf(false)
    val selectedBranch = mutableStateOf("")
    val showCreateWorktree = mutableStateOf(false)
    val newWorktreeBranch = mutableStateOf("")
    val confirmingPush = mutableStateOf(false)
    val archiving = mutableStateOf<String?>(null)
}

/** A yes/no question before something that reaches beyond the phone (a push, deleting a directory). */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.remote_common_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_common_cancel)) } },
    )
}

@Composable
internal fun SwitchBranchDialog(
    visible: Boolean,
    selectedBranch: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (visible && selectedBranch.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(stringResource(R.string.remote_git_switch_branch))
            },
            text = {
                Text(stringResource(R.string.remote_git_switch_confirm, selectedBranch))
            },
            confirmButton = {
                Button(onClick = onConfirm) {
                    Text(stringResource(R.string.remote_common_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.remote_common_cancel))
                }
            },
        )
    }
}

@Composable
internal fun CreateWorktreeDialog(
    visible: Boolean,
    branchName: String,
    onBranchChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (visible) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(stringResource(R.string.remote_git_create_worktree))
            },
            text = {
                OutlinedTextField(
                    value = branchName,
                    onValueChange = onBranchChange,
                    label = {
                        Text(stringResource(R.string.remote_git_branch_name))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = onConfirm,
                    enabled = branchName.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.remote_common_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.remote_common_cancel))
                }
            },
        )
    }
}
