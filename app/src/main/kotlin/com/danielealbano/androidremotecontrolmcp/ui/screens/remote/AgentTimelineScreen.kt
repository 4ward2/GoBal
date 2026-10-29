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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.AgentTimelineUiState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.AgentTimelineViewModel

/** The full record of one agent on a computer: tool calls with their output, edits as diffs, to-dos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTimelineScreen(
    computerId: String,
    agentId: String,
    onBack: () -> Unit,
    viewModel: AgentTimelineViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(computerId, agentId) { viewModel.load(computerId, agentId) }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        TopAppBar(
            title = { Text(stringResource(R.string.remote_timeline_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.remote_common_back))
                }
            },
            actions = {
                IconButton(onClick = { viewModel.load(computerId, agentId) }) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.remote_timeline_refresh))
                }
            },
            windowInsets = WindowInsets(0),
        )
        TimelineBody(ui)
    }
}

@Composable
private fun TimelineBody(ui: AgentTimelineUiState) {
    val error = ui.error
    when {
        ui.loading && ui.entries.isEmpty() -> {
            Centered { CircularProgressIndicator() }
        }

        error != null -> {
            Centered { Text(error, color = MaterialTheme.colorScheme.error) }
        }

        ui.entries.isEmpty() -> {
            Centered { Text(stringResource(R.string.remote_timeline_empty)) }
        }

        else -> {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                // The newest at the bottom, like the chat; the list opens there.
                reverseLayout = true,
            ) {
                itemsIndexed(ui.entries.asReversed(), key = { index, _ -> index }) { _, entry ->
                    TimelineEntryView(entry)
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}
