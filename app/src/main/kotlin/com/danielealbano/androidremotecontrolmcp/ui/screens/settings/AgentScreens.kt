@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentNotices
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRun
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AgentSettingsViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AppGrant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every phone task the assistant did: what was asked, each step it took, and how it ended. */
@Composable
fun TaskHistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentSettingsViewModel = hiltViewModel(),
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    Column(modifier = modifier.fillMaxSize()) {
        PageBar(stringResource(R.string.settings_tasks_title), onBack) {
            if (history.isNotEmpty()) {
                TextButton(onClick = viewModel::clearHistory) { Text(stringResource(R.string.settings_tasks_clear)) }
            }
        }
        if (history.isEmpty()) EmptyNote(stringResource(R.string.settings_tasks_empty))
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(history, key = { it.id }) { run -> TaskCard(run) }
        }
    }
}

/** One task; tapping it unfolds all of its steps. */
@Composable
private fun TaskCard(run: AgentRun) {
    var open by remember(run.id) { mutableStateOf(false) }
    Card(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(run.goal, style = MaterialTheme.typography.titleMedium)
            val faint = MaterialTheme.colorScheme.onSurfaceVariant
            val state = AgentNotices.title(run)
            val line = stringResource(R.string.settings_tasks_line, state, run.steps.size, dateOf(run.startedAt))
            Text(line, style = MaterialTheme.typography.labelLarge, color = faint)
            if (run.result.isNotBlank()) Text(run.result, style = MaterialTheme.typography.bodyLarge)
            if (open) {
                run.steps.forEachIndexed { index, step ->
                    val mark = if (step.ok) "" else stringResource(R.string.settings_tasks_step_failed)
                    Text("${index + 1}. ${step.label}$mark", style = MaterialTheme.typography.bodyMedium, color = faint)
                }
                val body = MaterialTheme.typography.bodyMedium
                run.notes.forEach { Text(stringResource(R.string.chat_task_note, it), style = body, color = faint) }
                if (run.archivedAs.isNotBlank()) {
                    Text(
                        stringResource(R.string.chat_task_script, run.archivedAs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * The apps the user answered "always" for, each with a switch: off means the assistant never
 * works in that app, and the bin forgets the answer so the app is asked about again.
 */
@Composable
fun AppGrantsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentSettingsViewModel = hiltViewModel(),
) {
    val grants by viewModel.grants.collectAsStateWithLifecycle()
    Column(modifier = modifier.fillMaxSize()) {
        PageBar(stringResource(R.string.settings_grants_title), onBack) {}
        Text(
            stringResource(R.string.settings_grants_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (grants.isEmpty()) EmptyNote(stringResource(R.string.settings_grants_empty))
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(grants, key = { it.packageName }) { grant -> GrantRow(grant, viewModel) }
        }
    }
}

@Composable
private fun GrantRow(
    grant: AppGrant,
    viewModel: AgentSettingsViewModel,
) {
    val state = stringResource(if (grant.allowed) R.string.settings_grants_allowed else R.string.settings_grants_denied)
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(grant.label, style = MaterialTheme.typography.titleMedium)
                val faint = MaterialTheme.colorScheme.onSurfaceVariant
                Text(state, style = MaterialTheme.typography.labelLarge, color = faint)
            }
            Switch(
                checked = grant.allowed,
                onCheckedChange = { viewModel.setAllowed(grant.packageName, it) },
                modifier = Modifier.semantics { contentDescription = "${grant.label}：$state" },
            )
            IconButton(onClick = { viewModel.setAllowed(grant.packageName, null) }) {
                val forget = stringResource(R.string.settings_grants_forget, grant.label)
                Icon(Icons.Default.Delete, contentDescription = forget)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
            }
        },
        actions = { actions() },
        windowInsets = WindowInsets(0),
    )
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
    )
}

private fun dateOf(millis: Long): String =
    if (millis <= 0) "" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
