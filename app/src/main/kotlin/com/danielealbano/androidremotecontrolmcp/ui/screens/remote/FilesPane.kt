@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.FileEntry
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.FilesUiState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.FilesViewModel

internal data class FilesPane(
    val filesState: FilesUiState,
    val currentPath: String,
    val computerId: String,
    val workspaceRoot: String,
)

@Composable
internal fun FilesBrowser(
    pane: FilesPane,
    viewModel: FilesViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        BreadcrumbNavigation(
            path = pane.currentPath,
            onNavigate = onNavigate,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(8.dp),
        )

        when {
            pane.filesState.isLoading -> {
                FilesBrowserLoading()
            }

            pane.filesState.error != null -> {
                FilesBrowserError(pane.filesState.error)
            }

            else -> {
                FilesBrowserList(
                    entries = pane.filesState.entries,
                    computerId = pane.computerId,
                    workspaceRoot = pane.workspaceRoot,
                    viewModel = viewModel,
                    onNavigate = onNavigate,
                )
            }
        }
    }
}

@Composable
private fun FilesBrowserLoading() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun FilesBrowserError(error: String?) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            error ?: stringResource(R.string.remote_files_error_unknown),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun FilesBrowserList(
    entries: List<FileEntry>,
    computerId: String,
    workspaceRoot: String,
    viewModel: FilesViewModel,
    onNavigate: (String) -> Unit,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(TAB_ROW_SPACING.dp),
    ) {
        items(entries, key = { it.path }) { entry ->
            FileEntryItem(
                entry = entry,
                onTapDirectory = { onNavigate(entry.path) },
                onTapFile = {
                    viewModel.readFile(computerId, entry.path, workspaceRoot)
                },
            )
        }
    }
}
