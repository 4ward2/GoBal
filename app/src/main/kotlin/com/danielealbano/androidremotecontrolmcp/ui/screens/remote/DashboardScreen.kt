@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.remote.hooks.GoalRun
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.DashboardViewModel

private const val ONE_MINUTE_MILLIS = 60_000L
private const val ONE_HOUR_MILLIS = 3600_000L
private const val ONE_DAY_MILLIS = 86400_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onBack: () -> Unit,
    onOpenAgent: (String, String) -> Unit,
    onOpenPhoneTask: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val rows = viewModel.rows.collectAsState()
    val grouped = rows.value.groupBy { it.state }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
    ) {
        TopAppBar(
            windowInsets = WindowInsets(0),
            title = { Text(stringResource(R.string.remote_entry_dashboard_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.remote_dashboard_back),
                    )
                }
            },
        )

        LazyColumn {
            for (state in DashboardRowState.entries) {
                val rowsInState = grouped[state] ?: emptyList()
                if (rowsInState.isEmpty()) continue

                item {
                    DashboardSectionHeader(state, rowsInState.size)
                }

                items(rowsInState, key = { "${it.source}:${it.title}" }) { row ->
                    DashboardRowItem(
                        row = row,
                        onOpenPhone = if (row.computerId == null) onOpenPhoneTask else null,
                        onOpenAgent = onOpenAgent,
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardSectionHeader(
    state: DashboardRowState,
    count: Int,
) {
    val label =
        when (state) {
            DashboardRowState.WAITING_FOR_YOU -> {
                stringResource(R.string.remote_dashboard_section_waiting)
            }

            DashboardRowState.WORKING -> {
                stringResource(R.string.remote_dashboard_section_working)
            }

            DashboardRowState.IDLE -> {
                stringResource(R.string.remote_dashboard_section_idle)
            }

            DashboardRowState.FAILED -> {
                stringResource(R.string.remote_dashboard_section_failed)
            }

            DashboardRowState.OFFLINE -> {
                stringResource(R.string.remote_dashboard_section_offline)
            }
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label ($count)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DashboardRowItem(
    row: DashboardRow,
    onOpenPhone: (() -> Unit)?,
    onOpenAgent: (String, String) -> Unit,
) {
    val onClick =
        if (row.computerId != null && row.agentId != null) {
            { onOpenAgent(row.computerId, row.agentId) }
        } else {
            onOpenPhone
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                ).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StateIndicator(state = row.state)

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = row.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            row.goal?.let { GoalLine(it) }
        }

        RelativeTime(millis = row.updatedAt)
    }
}

@Composable
private fun GoalLine(goal: GoalRun) {
    Text(
        text = goalStatus(goal),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun StateIndicator(state: DashboardRowState) {
    val color =
        when (state) {
            DashboardRowState.WORKING -> MaterialTheme.colorScheme.primary
            DashboardRowState.WAITING_FOR_YOU -> MaterialTheme.colorScheme.error
            DashboardRowState.IDLE -> MaterialTheme.colorScheme.outline
            DashboardRowState.FAILED -> MaterialTheme.colorScheme.error
            DashboardRowState.OFFLINE -> MaterialTheme.colorScheme.outlineVariant
        }
    Box(
        modifier =
            Modifier
                .size(8.dp)
                .background(color, shape = MaterialTheme.shapes.small),
    )
}

@Composable
private fun RelativeTime(millis: Long) {
    if (millis == 0L) return

    val now = System.currentTimeMillis()
    val diff = now - millis
    val text =
        when {
            diff < ONE_MINUTE_MILLIS -> "现在"
            diff < ONE_HOUR_MILLIS -> "${diff / ONE_MINUTE_MILLIS}分钟前"
            diff < ONE_DAY_MILLIS -> "${diff / ONE_HOUR_MILLIS}小时前"
            else -> "${diff / ONE_DAY_MILLIS}天前"
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
