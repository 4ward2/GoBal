@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.WorkspaceInfo
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.WorkspaceScript

/** What one workspace opens on the computer; each takes the workspace directory. */
enum class WorkspacePage { FILES, GIT, TERMINALS }

/** What a workspace card can do. */
internal data class WorkspaceActions(
    val onOpen: (WorkspacePage, WorkspaceInfo) -> Unit,
    val onRun: (WorkspaceInfo, WorkspaceScript) -> Unit,
    val onArchive: (WorkspaceInfo) -> Unit,
)

private data class PageButton(
    val page: WorkspacePage,
    val icon: ImageVector,
    val label: Int,
)

private val PAGES =
    listOf(
        PageButton(WorkspacePage.FILES, Icons.Default.Folder, R.string.remote_workspaces_open_folder),
        PageButton(WorkspacePage.GIT, Icons.Default.AccountTree, R.string.remote_workspaces_open_git),
        PageButton(WorkspacePage.TERMINALS, Icons.Default.Terminal, R.string.remote_workspaces_open_terminals),
    )

@Composable
internal fun WorkspaceCard(
    workspace: WorkspaceInfo,
    actions: WorkspaceActions,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(folderName(workspace), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.remote_workspaces_branch, workspace.name, workspace.workspaceDirectory),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                PAGES.forEach { button ->
                    IconButton(onClick = { actions.onOpen(button.page, workspace) }) {
                        Icon(button.icon, stringResource(button.label))
                    }
                }
                IconButton(onClick = { actions.onArchive(workspace) }) {
                    Icon(Icons.Default.Archive, stringResource(R.string.remote_workspaces_archive))
                }
            }
            if (workspace.scripts.isNotEmpty()) {
                Text(
                    stringResource(R.string.remote_workspaces_scripts),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                workspace.scripts.forEach { script -> ScriptRow(script) { actions.onRun(workspace, script) } }
            }
        }
    }
}

@Composable
private fun ScriptRow(
    script: WorkspaceScript,
    onRun: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(script.scriptName, style = MaterialTheme.typography.bodyMedium)
            val noPort = stringResource(R.string.remote_workspaces_no_port)
            val where = script.port?.let { "${script.hostname}:$it" } ?: noPort
            Text(where, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRun) {
            Icon(Icons.Default.PlayArrow, stringResource(R.string.remote_workspaces_run_script, script.scriptName))
        }
    }
}

/** The workspace's own folder name: the branch alone does not tell two checkouts of one repo apart. */
private fun folderName(workspace: WorkspaceInfo): String =
    workspace.title ?: workspace.workspaceDirectory
        .trimEnd('/', '\\')
        .substringAfterLast('/')
        .substringAfterLast('\\')
