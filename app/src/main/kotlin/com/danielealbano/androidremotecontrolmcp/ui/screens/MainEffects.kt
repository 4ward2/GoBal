@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.data.model.AppTier
import com.danielealbano.androidremotecontrolmcp.ui.LocalTier
import com.danielealbano.androidremotecontrolmcp.ui.call.CallOverlay
import com.danielealbano.androidremotecontrolmcp.ui.chat.AssistantDrawer
import com.danielealbano.androidremotecontrolmcp.ui.chat.CharacterActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.CharacterImportSheet
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatScreenActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.DrawerActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.DrawerCharacters
import com.danielealbano.androidremotecontrolmcp.ui.chat.ImportActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.LocalGroupOpener
import com.danielealbano.androidremotecontrolmcp.ui.chat.LocalMemoryOpener
import com.danielealbano.androidremotecontrolmcp.ui.chat.ProviderSetupPrompt
import com.danielealbano.androidremotecontrolmcp.ui.chat.VaultSavePrompt
import com.danielealbano.androidremotecontrolmcp.ui.navigation.ScreenTransitions
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SetupSteps
import com.danielealbano.androidremotecontrolmcp.ui.navigation.TabRequest
import com.danielealbano.androidremotecontrolmcp.ui.navigation.TopLevelRoute
import com.danielealbano.androidremotecontrolmcp.ui.navigation.swipeToGoBack
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AssistViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.CallViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.CharacterViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ConversationsViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MemoryFocus
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.NativeChatViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.TierViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The tier in force, read from the settings. Characters are not part of the simplest tier: it
 * always talks to the assistant. Going there from another page brings the user back to the chat,
 * the only page it has.
 */
@Composable
internal fun rememberTier(
    nav: MainNav,
    characterViewModel: CharacterViewModel,
): AppTier {
    val tierViewModel: TierViewModel = hiltViewModel()
    val read by tierViewModel.tier.collectAsStateWithLifecycle()
    val tier = read ?: AppTier.DEFAULT
    val simple = tier == AppTier.SIMPLE
    val activeCharacter by characterViewModel.activeId.collectAsStateWithLifecycle()
    LaunchedEffect(simple, activeCharacter) { if (simple && activeCharacter != null) characterViewModel.select(null) }
    LaunchedEffect(simple) { if (simple) nav.showChat() }
    return tier
}

/**
 * A tab asked for from outside (a notification, a deep link) is shown; back closes the drawer
 * first and from another page returns to the chat. Settings' own pages keep their nested back
 * stack: that handler is composed later, so it wins while it has pages to pop. During a call back
 * belongs to the call screen (it hangs up first).
 */
@Composable
internal fun NavEffects(
    nav: MainNav,
    requestedTabRoute: String?,
    onRequestedTabConsumed: () -> Unit,
    inCall: Boolean,
) {
    LaunchedEffect(requestedTabRoute) {
        val destination = TabRequest.resolve(requestedTabRoute ?: return@LaunchedEffect)
        nav.screenRoute = destination.tabRoute
        nav.pendingSettingsRoute = destination.settingsRoute
        onRequestedTabConsumed()
    }
    BackHandler(enabled = !inCall && (nav.drawerState.isOpen || nav.covered)) {
        if (nav.drawerState.isOpen) nav.closeDrawer() else nav.showChat()
    }
}
