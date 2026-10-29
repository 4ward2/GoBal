package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import com.danielealbano.androidremotecontrolmcp.ui.navigation.GeofenceRoutes
import com.danielealbano.androidremotecontrolmcp.ui.navigation.opaquePage
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.GeofenceSettingsViewModel

/** gms flavor: registers the geofence list + map settings destinations. */
fun NavGraphBuilder.geofenceDestinations(navController: NavHostController) {
    opaquePage(GeofenceRoutes.LIST) {
        val viewModel: GeofenceSettingsViewModel = hiltViewModel()
        GeofenceListScreen(
            viewModel = viewModel,
            onNavigateToMap = { zoneId -> navController.navigate(GeofenceRoutes.map(zoneId)) },
            onNavigateBack = { navController.popBackStack() },
        )
    }
    opaquePage(GeofenceRoutes.MAP_PATTERN) { backStackEntry ->
        val zoneId = backStackEntry.arguments?.getString("zoneId")?.ifEmpty { null }
        val viewModel: GeofenceSettingsViewModel = hiltViewModel()
        GeofenceMapScreen(
            viewModel = viewModel,
            zoneId = zoneId,
            onNavigateBack = { navController.popBackStack() },
        )
    }
}
