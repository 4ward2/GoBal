package com.danielealbano.androidremotecontrolmcp.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/**
 * A destination that paints its own solid background. While two pages slide past each other they
 * overlap for part of the width; a page without a background lets the other one show through there.
 */
fun NavGraphBuilder.opaquePage(
    route: String,
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route) { entry ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            content(entry)
        }
    }
}
