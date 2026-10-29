package com.danielealbano.androidremotecontrolmcp.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.danielealbano.androidremotecontrolmcp.data.model.AppTier

/** How much of the app is shown, for every screen under the main one; the host provides the real value. */
val LocalTier = staticCompositionLocalOf { AppTier.DEFAULT }
