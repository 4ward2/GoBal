@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRun
import com.danielealbano.androidremotecontrolmcp.services.agent.RunStatus

/**
 * A phone task in the conversation. The goal is its title; folded, the card says in one line
 * where the task stands. Opened, it shows the plan with how far each step got and what it
 * achieved, the map of what was searched and found, and the latest actions. Once the touches
 * were archived as a reusable script, the card says so. [run] is null for a task this app has
 * no record of (one done by the old helper process of a rooted phone).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhoneTaskCard(
    item: ChatItem.PhoneTask,
    actions: BubbleActions,
    run: AgentRun?,
) {
    var open by rememberSaveable(item.taskId) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(BubbleCorner, BubbleCorner, BubbleCorner, BubbleTail),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            Modifier
                .widthIn(max = MaxBubbleWidth)
                .combinedClickable(onClick = { actions.onTap(item) }, onLongClick = { actions.onLongPress(item) }),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TaskHeader()
            Text(
                item.request.ifBlank { stringResource(R.string.chat_task_untitled) },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (run == null) {
                StatusLine(Icons.Default.Info, stringResource(R.string.chat_task_no_record), running = false)
            } else {
                StatusLine(statusIcon(run.status), nowText(run), running = !run.status.finished)
                if (run.status.finished && run.result.isNotBlank()) {
                    Text(run.result, style = MaterialTheme.typography.bodyLarge)
                }
                if (run.archivedAs.isNotBlank()) ScriptNotice(run.archivedAs)
                DetailsToggle(open) { open = !open }
                if (open) TaskDetails(run)
                if (!run.status.finished) TaskButtons(actions)
            }
        }
    }
}

@Composable
private fun TaskHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.PhoneAndroid,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.chat_task_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The one line a folded card shows: where a running task is, or how a finished one ended. */
@Composable
private fun nowText(run: AgentRun): String =
    when (run.status) {
        RunStatus.RUNNING -> TaskCardText.now(run) ?: stringResource(R.string.chat_task_thinking)
        else -> stringResource(statusLabel(run.status))
    }

/** The touches of this task were archived: the same request is replayed next time without a model. */
@Composable
private fun ScriptNotice(name: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.Verified,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    stringResource(R.string.chat_task_script, name),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    stringResource(R.string.chat_task_script_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun DetailsToggle(
    open: Boolean,
    onToggle: () -> Unit,
) {
    TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = BUTTON_HEIGHT)) {
        Text(stringResource(if (open) R.string.chat_task_hide_details else R.string.chat_task_show_details))
        Spacer(Modifier.width(4.dp))
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
}

private fun statusLabel(status: RunStatus): Int =
    when (status) {
        RunStatus.RUNNING -> R.string.chat_task_running
        RunStatus.WAITING_APPROVAL -> R.string.chat_task_waiting
        RunStatus.DONE -> R.string.chat_task_done
        RunStatus.FAILED -> R.string.chat_task_failed
        RunStatus.STOPPED -> R.string.chat_task_stopped
        RunStatus.HANDED_OVER -> R.string.chat_task_handed_over
    }

private fun statusIcon(status: RunStatus): ImageVector =
    when (status) {
        RunStatus.RUNNING, RunStatus.WAITING_APPROVAL, RunStatus.DONE -> Icons.Default.CheckCircle
        RunStatus.FAILED -> Icons.Default.ErrorOutline
        RunStatus.STOPPED -> Icons.Default.StopCircle
        RunStatus.HANDED_OVER -> Icons.Default.PanTool
    }

@Composable
private fun StatusLine(
    icon: ImageVector,
    text: String,
    running: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (running) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            val tint = MaterialTheme.colorScheme.tertiary
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Stop it, or take the phone back and carry on by hand. Tall enough to hit without aiming. */
@Composable
private fun TaskButtons(actions: BubbleActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = actions.task.onStop, modifier = Modifier.heightIn(min = BUTTON_HEIGHT)) {
            Text(stringResource(R.string.chat_task_stop))
        }
        FilledTonalButton(onClick = actions.task.onTakeOver, modifier = Modifier.heightIn(min = BUTTON_HEIGHT)) {
            Text(stringResource(R.string.chat_task_take_over))
        }
    }
}

private val BUTTON_HEIGHT = 48.dp
