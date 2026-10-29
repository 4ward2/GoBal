@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.CheckoutDiff
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.DiffFile
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.DiffLine
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.FilesViewModel

private enum class Tab {
    Files,
    Changes,
}

/**
 * File explorer and diff viewer for a remote computer.
 * Shows file listing with breadcrumb navigation and a separate tab for checkout diff.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    computerId: String,
    startPath: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    var selectedTab by remember { mutableStateOf(Tab.Files) }
    var currentPath by remember { mutableStateOf(startPath) }

    val filesState by viewModel.filesState.collectAsState()
    val diffState by viewModel.diffState.collectAsState()

    LaunchedEffect(computerId, currentPath) {
        viewModel.open(computerId, currentPath, startPath)
    }

    // A repository's whole diff can be megabytes in one frame: fetch it only while 「改动」 is open,
    // so the file list and the connection stay quick.
    DisposableEffect(selectedTab) {
        if (selectedTab == Tab.Changes) viewModel.startWatchingDiff(computerId, startPath)
        onDispose { if (selectedTab == Tab.Changes) viewModel.stopWatchingDiff(computerId) }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
    ) {
        FilesScreenTopBar(onBack = onBack)
        FilesScreenTabs(
            selectedTab = selectedTab,
            onTabChange = { selectedTab = it },
        )
        FilesScreenTabContent(
            selectedTab = selectedTab,
            pane =
                FilesPane(
                    filesState = filesState,
                    currentPath = currentPath,
                    computerId = computerId,
                    workspaceRoot = startPath,
                ),
            diffState = diffState,
            viewModel = viewModel,
            onNavigate = { currentPath = it },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesScreenTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.remote_files_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back),
                )
            }
        },
        windowInsets = WindowInsets(0),
    )
}

@Composable
private fun FilesScreenTabs(
    selectedTab: Tab,
    onTabChange: (Tab) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = if (selectedTab == Tab.Files) 0 else 1) {
        Tab(
            selected = selectedTab == Tab.Files,
            onClick = { onTabChange(Tab.Files) },
            text = { Text(stringResource(R.string.remote_files_tab_files)) },
        )
        Tab(
            selected = selectedTab == Tab.Changes,
            onClick = { onTabChange(Tab.Changes) },
            text = { Text(stringResource(R.string.remote_files_tab_changes)) },
        )
    }
}

@Composable
private fun FilesScreenTabContent(
    selectedTab: Tab,
    pane: FilesPane,
    diffState: com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.DiffUiState,
    viewModel: FilesViewModel,
    onNavigate: (String) -> Unit,
) {
    when (selectedTab) {
        Tab.Files -> {
            Column(modifier = Modifier.fillMaxSize()) {
                FilesBrowser(
                    pane = pane,
                    viewModel = viewModel,
                    onNavigate = onNavigate,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                )

                if (pane.filesState.openedFile != null) {
                    FileContentViewer(
                        fileContent = pane.filesState.openedFile,
                        onClose = { viewModel.closeFile() },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(FILE_CONTENT_HEIGHT)
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                    )
                }
            }
        }

        Tab.Changes -> {
            DiffViewer(
                diffState = diffState,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun DiffViewer(
    diffState: com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.DiffUiState,
    modifier: Modifier = Modifier,
) {
    when {
        diffState.isLoading -> {
            Box(
                modifier =
                    modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        diffState.error != null -> {
            Box(
                modifier =
                    modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    diffState.error ?: stringResource(R.string.remote_files_error_unknown),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        diffState.diff != null -> {
            LazyColumn(
                modifier =
                    modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(diffState.diff.files, key = { it.path }) { file ->
                    DiffFileView(file)
                }
            }
        }

        else -> {
            Box(
                modifier =
                    modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.remote_files_no_changes))
            }
        }
    }
}

@Composable
private fun DiffFileView(
    file: DiffFile,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            file.path,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(8.dp),
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "+${file.additions}",
                color = MaterialTheme.colorScheme.surfaceTint,
                fontSize = 12.sp,
            )
            Text(
                "-${file.deletions}",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
        }

        file.hunks.forEach { hunk ->
            DiffHunkView(hunk)
        }
    }
}

@Composable
private fun DiffHunkView(
    hunk: com.danielealbano.androidremotecontrolmcp.services.remote.paseo.DiffHunk,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(
            "@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.outline,
            fontFamily = FontFamily.Monospace,
        )

        hunk.lines.forEach { line ->
            DiffLineView(line)
        }
    }
}
