@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.chat.MarkdownPalette

@Composable
fun ToolCallStatusIcon(
    status: String,
    modifier: Modifier = Modifier,
) {
    when (status) {
        "running" -> {
            CircularProgressIndicator(
                modifier = modifier.then(Modifier.size(16.dp)),
                strokeWidth = 2.dp,
            )
        }

        "completed" -> {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.remote_timeline_status_completed),
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier.then(Modifier.size(16.dp)),
            )
        }

        "failed" -> {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = stringResource(R.string.remote_timeline_status_failed),
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier.then(Modifier.size(16.dp)),
            )
        }

        else -> {
            Icon(
                imageVector = Icons.Outlined.Block,
                contentDescription = stringResource(R.string.remote_timeline_status_canceled),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.then(Modifier.size(16.dp)),
            )
        }
    }
}

@Composable
fun TodoItemStatusIcon(
    completed: Boolean,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector =
            if (completed) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
        contentDescription =
            if (completed) {
                stringResource(R.string.remote_timeline_todo_completed)
            } else {
                stringResource(R.string.remote_timeline_todo_pending)
            },
        modifier = modifier.then(Modifier.size(16.dp)),
        tint =
            if (completed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}

/** The chat's own Markdown rendering, so an agent's reply reads the same here as in its chat. */
@Composable
internal fun timelinePalette(): MarkdownPalette =
    MarkdownPalette(link = MaterialTheme.colorScheme.primary, codeBackground = MaterialTheme.colorScheme.surfaceVariant)
