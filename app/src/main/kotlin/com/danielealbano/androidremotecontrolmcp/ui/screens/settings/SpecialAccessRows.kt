@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.utils.SpecialAccess

/** Overlay and assistant access: granted in system settings, so each row says what it is for. */
@Composable
internal fun SpecialAccessRows() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var overlay by remember { mutableStateOf(SpecialAccess.canDrawOverlays(context)) }
    var assistant by remember { mutableStateOf(SpecialAccess.isAssistant(context)) }
    // The user changes these in another app; look again every time this page comes back.
    DisposableEffect(owner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    overlay = SpecialAccess.canDrawOverlays(context)
                    assistant = SpecialAccess.isAssistant(context)
                }
            }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Spacer(modifier = Modifier.height(8.dp))
    AccessRow(R.string.permission_assistant, R.string.permission_assistant_hint, assistant) {
        SpecialAccess.openAssistantSettings(context)
    }
    Spacer(modifier = Modifier.height(8.dp))
    AccessRow(R.string.permission_overlay, R.string.permission_overlay_hint, overlay) {
        SpecialAccess.openOverlaySettings(context)
    }
}

@Composable
private fun AccessRow(
    label: Int,
    hint: Int,
    granted: Boolean,
    onOpen: () -> Unit,
) {
    val button = if (granted) R.string.permission_enabled else R.string.permission_open_settings
    PermissionRow(
        label = stringResource(label),
        isEnabled = granted,
        buttonText = stringResource(button),
        onAction = onOpen,
        actionEnabled = !granted,
    )
    Text(
        text = stringResource(hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 32.dp),
    )
}
