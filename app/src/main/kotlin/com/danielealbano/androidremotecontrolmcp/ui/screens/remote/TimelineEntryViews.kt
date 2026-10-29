@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.TimelineEntry
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.TodoItem
import com.danielealbano.androidremotecontrolmcp.ui.chat.rememberMarkdown

private const val ROTATE_EXPANDED = 180f
private const val ROTATE_COLLAPSED = 0f

@Composable
fun TimelineEntryView(
    entry: TimelineEntry,
    modifier: Modifier = Modifier,
) {
    when (entry) {
        is TimelineEntry.UserMessage -> UserMessageView(entry, modifier)
        is TimelineEntry.AssistantMessage -> AssistantMessageView(entry, modifier)
        is TimelineEntry.Reasoning -> ReasoningView(entry, modifier)
        is TimelineEntry.ToolCall -> ToolCallView(entry, modifier)
        is TimelineEntry.Todo -> TodoView(entry, modifier)
        is TimelineEntry.Error -> ErrorView(entry, modifier)
        is TimelineEntry.Notification -> NotificationView(entry, modifier)
        is TimelineEntry.Compaction -> CompactionView(entry, modifier)
        is TimelineEntry.Plugin -> PluginView(entry, modifier)
    }
}

@Composable
private fun UserMessageView(
    entry: TimelineEntry.UserMessage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(8.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ).padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.remote_timeline_user),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        SelectionContainer {
            Text(
                text = rememberMarkdown(entry.text, timelinePalette()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AssistantMessageView(
    entry: TimelineEntry.AssistantMessage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(8.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ).padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.remote_timeline_assistant),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(modifier = Modifier.height(4.dp))
        SelectionContainer {
            Text(
                text = rememberMarkdown(entry.text, timelinePalette()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ReasoningView(
    entry: TimelineEntry.Reasoning,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(8.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .background(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.remote_timeline_reasoning),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.rotate(if (expanded) ROTATE_EXPANDED else ROTATE_COLLAPSED),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        AnimatedVisibility(visible = expanded) {
            SelectionContainer(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small,
                        ).padding(12.dp),
            ) {
                Text(
                    text = rememberMarkdown(entry.text, timelinePalette()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun ToolCallView(
    entry: TimelineEntry.ToolCall,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(8.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .background(
                        color =
                            when (entry.status) {
                                "running" -> MaterialTheme.colorScheme.secondaryContainer
                                "completed" -> MaterialTheme.colorScheme.tertiaryContainer
                                "failed" -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                        shape = MaterialTheme.shapes.medium,
                    ).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolCallStatusIcon(
                status = entry.status,
                modifier = Modifier.width(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.rotate(if (expanded) ROTATE_EXPANDED else ROTATE_COLLAPSED),
            )
        }
        AnimatedVisibility(visible = expanded) {
            ToolDetailView(detail = entry.detail)
        }
    }
}

@Composable
private fun TodoView(
    entry: TimelineEntry.Todo,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(8.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ).padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.remote_timeline_todo),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        entry.items.forEach { item ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TodoItemStatusIcon(
                    completed = item.completed,
                    modifier = Modifier.width(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ErrorView(
    entry: TimelineEntry.Error,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(8.dp)
                .background(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ).padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.remote_timeline_error),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(modifier = Modifier.height(4.dp))
        SelectionContainer {
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun NotificationView(
    entry: TimelineEntry.Notification,
    modifier: Modifier = Modifier,
) {
    val backgroundColor =
        when (entry.level) {
            "error" -> MaterialTheme.colorScheme.errorContainer
            "warning" -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        }
    val contentColor =
        when (entry.level) {
            "error" -> MaterialTheme.colorScheme.onErrorContainer
            "warning" -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onSecondaryContainer
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(8.dp)
                .background(color = backgroundColor, shape = MaterialTheme.shapes.medium)
                .padding(12.dp),
    ) {
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
        )
    }
}

@Composable
private fun CompactionView(
    entry: TimelineEntry.Compaction,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(8.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ).border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.remote_timeline_compaction),
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = entry.summary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PluginView(
    entry: TimelineEntry.Plugin,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(8.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ).padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.remote_timeline_plugin),
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "ID: ${entry.id}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Kind: ${entry.kind}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
