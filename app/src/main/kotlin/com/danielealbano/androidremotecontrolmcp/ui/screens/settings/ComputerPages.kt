@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavController
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.screens.remote.BindAsk
import com.danielealbano.androidremotecontrolmcp.ui.screens.remote.ComputerPage
import com.danielealbano.androidremotecontrolmcp.ui.screens.remote.RemoteRoutes
import kotlinx.coroutines.flow.StateFlow

/**
 * How 「我的电脑」 reaches the pages of one computer, and hears back from the setup guide which
 * way the user chose to bind. Kept out of the screen so it stays a plain list of cards.
 */
class ComputerPages(
    private val navController: NavController,
    private val state: SavedStateHandle,
) {
    /** The guide's choice, or "" when there is none waiting. */
    val asks: StateFlow<String> = state.getStateFlow(RemoteRoutes.ASK_KEY, "")

    fun consumeAsk() {
        state[RemoteRoutes.ASK_KEY] = ""
    }

    fun open(
        computerId: String,
        page: ComputerPage,
    ) = navController.navigate(RemoteRoutes.page(computerId, page))

    fun manage(
        computerId: String,
        agentId: String,
    ) = navController.navigate(RemoteRoutes.agent(computerId, agentId))

    fun guide() = navController.navigate(RemoteRoutes.CONNECT)

    companion object {
        fun askOf(name: String): BindAsk? = BindAsk.entries.firstOrNull { it.name == name }
    }
}

private data class Tool(
    val page: ComputerPage,
    val icon: ImageVector,
    val label: Int,
)

private val TOOLS =
    listOf(
        Tool(ComputerPage.WORKSPACES, Icons.Default.Folder, R.string.remote_entry_workspaces),
        Tool(ComputerPage.DAEMON, Icons.Default.Settings, R.string.remote_entry_daemon),
    )

/** Icon buttons for a computer's workspaces, terminals and Paseo settings; shown while it is online. */
@Composable
internal fun ComputerTools(
    computerId: String,
    pages: ComputerPages,
) {
    Row(modifier = Modifier.padding(horizontal = 8.dp)) {
        TOOLS.forEach { tool ->
            IconButton(onClick = { pages.open(computerId, tool.page) }) {
                Icon(tool.icon, contentDescription = stringResource(tool.label))
            }
        }
    }
}

/** Opens an agent's own page: rename, mode, model, archive. */
@Composable
internal fun ManageAgentButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.remote_entry_manage_agent))
    }
}
