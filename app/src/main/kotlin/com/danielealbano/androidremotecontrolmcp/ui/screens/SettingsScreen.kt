@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.danielealbano.androidremotecontrolmcp.ui.navigation.ScreenTransitions
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute
import com.danielealbano.androidremotecontrolmcp.ui.navigation.opaquePage
import com.danielealbano.androidremotecontrolmcp.ui.screens.remote.remoteDestinations
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.AccessSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.AppGrantsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.AppearanceScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.AssistSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.BackupScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.ChannelSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.ChatSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.ComputerPages
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.GeneralSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.McpToolsSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.MemoryLibraryScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.NotificationFilterScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.OAuthClientsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.PermissionsSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.ProviderSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.RemoteComputersScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.SecuritySettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.SettingsIndexScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.ShellSetupScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.SkillsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.StorageSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.TaskHistoryScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.TierScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.TunnelSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.VaultSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.WeixinScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.WifiMonitorScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.geofenceDestinations
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ChannelViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel

@Composable
fun SettingsScreen(
    onRequestNotificationPermission: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    pendingRoute: String? = null,
    onPendingRouteConsumed: () -> Unit = {},
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
    channelViewModel: ChannelViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()

    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            navController.navigate(pendingRoute) {
                launchSingleTop = true
            }
            onPendingRouteConsumed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = SettingsRoute.Index.route,
        modifier = modifier,
        enterTransition = { ScreenTransitions.pushEnter },
        exitTransition = { ScreenTransitions.pushExit },
        popEnterTransition = { ScreenTransitions.popEnter },
        popExitTransition = { ScreenTransitions.popExit },
    ) {
        opaquePage(SettingsRoute.Index.route) {
            SettingsIndexScreen(onNavigate = { navController.navigate(it) }, onBack = onClose)
        }
        opaquePage(SettingsRoute.Chat.route) {
            ChatSettingsScreen(
                onBack = { navController.popBackStack() },
                onRequestMicrophonePermission = onRequestMicrophonePermission,
            )
        }
        opaquePage(SettingsRoute.Providers.route) {
            ProviderSettingsScreen(onBack = { navController.popBackStack() })
        }
        opaquePage(SettingsRoute.Server.route) {
            ServerScreen(
                onNavigateToPermissions = { navController.navigate(SettingsRoute.Permissions.route) },
                onBack = { navController.popBackStack() },
                viewModel = viewModel,
            )
        }
        opaquePage(SettingsRoute.About.route) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        opaquePage(SettingsRoute.Assist.route) {
            AssistSettingsScreen(onBack = { navController.popBackStack() })
        }
        opaquePage(SettingsRoute.Skills.route) {
            SkillsScreen(onBack = { navController.popBackStack() })
        }
        opaquePage(SettingsRoute.TaskHistory.route) {
            TaskHistoryScreen(onBack = { navController.popBackStack() })
        }
        opaquePage(SettingsRoute.AppGrants.route) {
            AppGrantsScreen(onBack = { navController.popBackStack() })
        }
        opaquePage(SettingsRoute.Appearance.route) {
            AppearanceScreen(onBack = { navController.popBackStack() })
        }
        opaquePage(SettingsRoute.Mode.route) { TierScreen(onBack = { navController.popBackStack() }) }
        opaquePage(SettingsRoute.Weixin.route) { WeixinScreen(onBack = { navController.popBackStack() }) }
        opaquePage(SettingsRoute.Shell.route) {
            ShellSetupScreen(
                onBack = { navController.popBackStack() },
                onRequestNotificationPermission = onRequestNotificationPermission,
            )
        }
        opaquePage(SettingsRoute.Computers.route) { entry ->
            RemoteComputersScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = onClose,
                onRequestCameraPermission = onRequestCameraPermission,
                pages = ComputerPages(navController, entry.savedStateHandle),
            )
        }
        opaquePage(SettingsRoute.MemoryLibrary.route) {
            MemoryLibraryScreen(onBack = { navController.popBackStack() })
        }
        opaquePage(SettingsRoute.Backup.route) {
            BackupScreen(onBack = { navController.popBackStack() })
        }
        opaquePage(SettingsRoute.Vault.route) {
            VaultSettingsScreen(onBack = { navController.popBackStack() })
        }
        opaquePage(SettingsRoute.General.route) {
            GeneralSettingsScreen(onBack = { navController.popBackStack() }, viewModel = viewModel)
        }
        opaquePage(SettingsRoute.Access.route) {
            AccessSettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateClients = { navController.navigate(SettingsRoute.OAuthClients.route) },
            )
        }
        opaquePage(SettingsRoute.OAuthClients.route) {
            OAuthClientsScreen(onBack = { navController.popBackStack() })
        }
        opaquePage(SettingsRoute.Security.route) {
            SecuritySettingsScreen(onBack = { navController.popBackStack() }, viewModel = viewModel)
        }
        opaquePage(SettingsRoute.Tunnel.route) {
            TunnelSettingsScreen(onBack = { navController.popBackStack() }, viewModel = viewModel)
        }
        opaquePage(SettingsRoute.McpTools.route) {
            McpToolsSettingsScreen(onBack = { navController.popBackStack() })
        }
        opaquePage(SettingsRoute.Permissions.route) {
            PermissionsSettingsScreen(
                onBack = { navController.popBackStack() },
                onRequestNotificationPermission = onRequestNotificationPermission,
                onRequestCameraPermission = onRequestCameraPermission,
                onRequestMicrophonePermission = onRequestMicrophonePermission,
                onRequestLocationPermission = onRequestLocationPermission,
                viewModel = viewModel,
            )
        }
        opaquePage(SettingsRoute.Storage.route) {
            StorageSettingsScreen(onBack = { navController.popBackStack() }, viewModel = viewModel)
        }
        opaquePage(SettingsRoute.ChannelSettings.route) {
            ChannelSettingsScreen(
                viewModel = channelViewModel,
                navController = navController,
                onNavigateToNotificationFilter = {
                    navController.navigate(SettingsRoute.NotificationFilter.route)
                },
                onNavigateToWifiMonitor = {
                    navController.navigate(SettingsRoute.WifiMonitor.route)
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }
        opaquePage(SettingsRoute.NotificationFilter.route) {
            NotificationFilterScreen(
                viewModel = channelViewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }
        opaquePage(SettingsRoute.WifiMonitor.route) {
            WifiMonitorScreen(
                viewModel = channelViewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }
        geofenceDestinations(navController)
        remoteDestinations(navController, onOpenPhoneTask = onClose)
    }
}
