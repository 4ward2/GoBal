package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import android.net.Uri
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute
import com.danielealbano.androidremotecontrolmcp.ui.navigation.opaquePage

/** The pages a bound computer opens from its card on 「我的电脑」. */
enum class ComputerPage(
    internal val segment: String,
) {
    WORKSPACES("workspaces"),
    DAEMON("daemon"),
}

/**
 * Where the developer-tier pages for computers live in the settings graph. Every argument is a
 * path segment, encoded, so a Windows path with slashes and colons survives the trip.
 */
object RemoteRoutes {
    val DASHBOARD = SettingsRoute.Dashboard.route
    const val CONNECT = "settings/remote/connect"

    /** What the guide asks 「我的电脑」 to open when the user goes back to bind. */
    const val ASK_KEY = "remote_ask"

    private const val COMPUTER = "computer"
    private const val AGENT = "agent"
    private const val PATH = "path"
    private const val BASE = "settings/remote/{$COMPUTER}"

    internal val pagePatterns = ComputerPage.entries.associateWith { "$BASE/${it.segment}" }
    internal const val AGENT_PATTERN = "$BASE/agent/{$AGENT}"
    internal const val TIMELINE_PATTERN = "$BASE/timeline/{$AGENT}"
    internal const val FILES_PATTERN = "$BASE/files/{$PATH}"
    internal const val GIT_PATTERN = "$BASE/git/{$PATH}"
    internal const val TERMINALS_PATTERN = "$BASE/terminals/{$PATH}"

    fun page(
        computerId: String,
        page: ComputerPage,
    ): String = "settings/remote/${Uri.encode(computerId)}/${page.segment}"

    fun agent(
        computerId: String,
        agentId: String,
    ): String = "settings/remote/${Uri.encode(computerId)}/agent/${Uri.encode(agentId)}"

    fun timeline(
        computerId: String,
        agentId: String,
    ): String = "settings/remote/${Uri.encode(computerId)}/timeline/${Uri.encode(agentId)}"

    /** One of a workspace's own pages, for the workspace directory [path]. */
    fun workspace(
        computerId: String,
        page: WorkspacePage,
        path: String,
    ): String {
        val segment = page.name.lowercase()
        return "settings/remote/${Uri.encode(computerId)}/$segment/${Uri.encode(path)}"
    }

    internal fun computer(entry: NavBackStackEntry): String = entry.text(COMPUTER)

    internal fun agent(entry: NavBackStackEntry): String = entry.text(AGENT)

    internal fun path(entry: NavBackStackEntry): String = entry.text(PATH)

    private fun NavBackStackEntry.text(key: String): String = arguments?.getString(key).orEmpty()
}

/** The guide's three ways to bind, handed back to 「我的电脑」 which owns the dialogs. */
enum class BindAsk { SCAN, PASTE, MANUAL }

/** Registers every page a bound computer has: workspaces, terminals, files, git, its settings, one agent. */
fun NavGraphBuilder.remoteDestinations(
    navController: NavController,
    onOpenPhoneTask: () -> Unit,
) {
    val back: () -> Unit = { navController.popBackStack() }
    opaquePage(RemoteRoutes.DASHBOARD) {
        DashboardScreen(
            onBack = back,
            onOpenAgent = { computer, agent -> navController.navigate(RemoteRoutes.agent(computer, agent)) },
            onOpenPhoneTask = onOpenPhoneTask,
        )
    }
    opaquePage(RemoteRoutes.CONNECT) {
        val bind: (BindAsk) -> Unit = { ask ->
            navController.previousBackStackEntry?.savedStateHandle?.set(RemoteRoutes.ASK_KEY, ask.name)
            navController.popBackStack()
        }
        ConnectComputerScreen(
            onBack = back,
            onScan = { bind(BindAsk.SCAN) },
            onPasteLink = { bind(BindAsk.PASTE) },
            onManual = { bind(BindAsk.MANUAL) },
        )
    }
    computerPages(navController, back)
}

private fun NavGraphBuilder.computerPages(
    navController: NavController,
    back: () -> Unit,
) {
    opaquePage(checkNotNull(RemoteRoutes.pagePatterns[ComputerPage.WORKSPACES])) { entry ->
        val computer = RemoteRoutes.computer(entry)
        WorkspacesScreen(
            computerId = computer,
            onBack = back,
            onOpen = { page, path -> navController.navigate(RemoteRoutes.workspace(computer, page, path)) },
        )
    }
    opaquePage(RemoteRoutes.TERMINALS_PATTERN) { entry ->
        TerminalScreen(computerId = RemoteRoutes.computer(entry), cwd = RemoteRoutes.path(entry), onBack = back)
    }
    opaquePage(checkNotNull(RemoteRoutes.pagePatterns[ComputerPage.DAEMON])) { entry ->
        DaemonAdminScreen(computerId = RemoteRoutes.computer(entry), onBack = back)
    }
    opaquePage(RemoteRoutes.AGENT_PATTERN) { entry ->
        val computer = RemoteRoutes.computer(entry)
        val agent = RemoteRoutes.agent(entry)
        AgentAdminScreen(
            computerId = computer,
            agentId = agent,
            onBack = back,
            onOpenTimeline = { navController.navigate(RemoteRoutes.timeline(computer, agent)) },
        )
    }
    opaquePage(RemoteRoutes.TIMELINE_PATTERN) { entry ->
        val computer = RemoteRoutes.computer(entry)
        AgentTimelineScreen(computerId = computer, agentId = RemoteRoutes.agent(entry), onBack = back)
    }
    opaquePage(RemoteRoutes.FILES_PATTERN) { entry ->
        FilesScreen(computerId = RemoteRoutes.computer(entry), startPath = RemoteRoutes.path(entry), onBack = back)
    }
    opaquePage(RemoteRoutes.GIT_PATTERN) { entry ->
        GitScreen(computerId = RemoteRoutes.computer(entry), workspacePath = RemoteRoutes.path(entry), onBack = back)
    }
}
