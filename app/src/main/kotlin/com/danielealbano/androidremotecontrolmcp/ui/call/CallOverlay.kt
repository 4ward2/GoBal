@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import com.danielealbano.androidremotecontrolmcp.ui.screens.CallScreen
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.CallViewModel

/** The call lies over whatever is on screen and fades in and out; the chat stays put underneath. */
@Composable
fun CallOverlay(
    visible: Boolean,
    contentPadding: PaddingValues,
    viewModel: CallViewModel,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(OVERLAY_FADE_MS)),
        exit = fadeOut(tween(OVERLAY_FADE_MS)),
    ) {
        CallScreen(contentPadding = contentPadding, viewModel = viewModel)
    }
}

private const val OVERLAY_FADE_MS = 200
