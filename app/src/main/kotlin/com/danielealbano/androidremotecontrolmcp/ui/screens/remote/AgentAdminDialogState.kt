@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.AgentAdminViewModel

@Stable
class AgentAdminDialogState {
    val showDelete = mutableStateOf(false)
    val showArchive = mutableStateOf(false)
}

@Composable
internal fun AgentAdminDeleteDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (onBackAfterDelete: () -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    if (visible) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.remote_agentadmin_delete_confirm_title)) },
            text = { Text(stringResource(R.string.remote_agentadmin_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = { onConfirm(onBack) },
                ) {
                    Text(stringResource(R.string.remote_agentadmin_delete))
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
internal fun AgentAdminArchiveDialog(
    visible: Boolean,
    archived: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (isArchiving: Boolean) -> Unit,
) {
    if (visible) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    if (archived) {
                        stringResource(R.string.remote_agentadmin_resume_confirm_title)
                    } else {
                        stringResource(R.string.remote_agentadmin_archive_confirm_title)
                    },
                )
            },
            text = {
                Text(
                    if (archived) {
                        stringResource(R.string.remote_agentadmin_resume_confirm_body)
                    } else {
                        stringResource(R.string.remote_agentadmin_archive_confirm_body)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onConfirm(!archived) },
                ) {
                    Text(
                        if (archived) {
                            stringResource(R.string.remote_agentadmin_resume)
                        } else {
                            stringResource(R.string.remote_agentadmin_archive)
                        },
                    )
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
