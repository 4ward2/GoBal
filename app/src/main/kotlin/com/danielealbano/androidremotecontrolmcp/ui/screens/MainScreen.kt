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

/** The chat is the root; Settings and the contact page sit on top of it, so leaving the chat is a push. */
private fun isForward(
    from: String,
    to: String,
): Boolean = from == TopLevelRoute.Chat.route && to != TopLevelRoute.Chat.route

/** Runtime-permission prompts owned by the activity. */
class PermissionRequests(
    val notification: () -> Unit,
    val camera: () -> Unit,
    val microphone: () -> Unit,
    val location: () -> Unit,
    val mediaImages: () -> Unit,
)

/** Where the app is: which screen, which Settings sub-page was deep-linked, whether the drawer is open. */
@Stable
internal class MainNav(
    val drawerState: DrawerState,
    private val scope: CoroutineScope,
    screenRoute: MutableState<String>,
    pendingSettingsRoute: MutableState<String?>,
) {
    var screenRoute by screenRoute
    var pendingSettingsRoute by pendingSettingsRoute

    val onSettings: Boolean get() = screenRoute == TopLevelRoute.Settings.route
    val onContact: Boolean get() = screenRoute == TopLevelRoute.Contact.route
    val onGroup: Boolean get() = screenRoute == TopLevelRoute.Group.route

    /** Something lies over the chat: back and the swipe from the left then mean "back to the chat". */
    val covered: Boolean get() = onSettings || onContact || onGroup

    /** True when the group screen was asked for in order to make a new group. */
    var newGroup by mutableStateOf(false)
        private set

    fun openDrawer() {
        scope.launch { drawerState.open() }
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    fun showChat() {
        screenRoute = TopLevelRoute.Chat.route
    }

    fun showSettings() {
        screenRoute = TopLevelRoute.Settings.route
    }

    fun showContact() {
        screenRoute = TopLevelRoute.Contact.route
    }

    /** The group chats; [makeNew] goes straight to the page that makes one. */
    fun showGroups(makeNew: Boolean) {
        newGroup = makeNew
        screenRoute = TopLevelRoute.Group.route
    }

    /** Opens Settings straight on one of its pages. */
    fun openSettings(route: SettingsRoute) {
        pendingSettingsRoute = route.route
        showSettings()
    }
}

@Composable
private fun rememberMainNav(): MainNav {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val screenRoute = rememberSaveable { mutableStateOf(TabRequest.DEFAULT.tabRoute) }
    val pendingSettingsRoute = rememberSaveable { mutableStateOf<String?>(null) }
    return remember(drawerState, scope) { MainNav(drawerState, scope, screenRoute, pendingSettingsRoute) }
}

/**
 * The chat is the app. Everything else hangs off the side drawer opened from the assistant's
 * avatar: the characters to talk to, conversation actions, and Settings at the bottom.
 *
 * @param mediaPermissionGrants bumped by the activity each time the images permission is granted,
 *   so an open import sheet can look for cards right away.
 */
@Composable
fun MainScreen(
    permissionRequests: PermissionRequests,
    requestedTabRoute: String? = null,
    onRequestedTabConsumed: () -> Unit = {},
    mediaPermissionGrants: Int = 0,
    viewModel: MainViewModel = hiltViewModel(),
) {
    // Activity-scoped: the same instances ChatScreen uses, so the draft survives screen switches.
    val chatViewModel: NativeChatViewModel = hiltViewModel()
    val assistViewModel: AssistViewModel = hiltViewModel()
    val characterViewModel: CharacterViewModel = hiltViewModel()
    val callViewModel: CallViewModel = hiltViewModel()
    val conversations: ConversationsViewModel = hiltViewModel()
    val inCall by callViewModel.active.collectAsStateWithLifecycle()
    // Goes wherever the back key would: a Settings sub-page pops first, then the chat.
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val swipeBack = remember(backDispatcher) { Modifier.swipeToGoBack { backDispatcher?.onBackPressed() } }
    val assistantName by assistViewModel.assistantName.collectAsStateWithLifecycle()
    val nav = rememberMainNav()
    val tier = rememberTier(nav, characterViewModel)
    val simple = tier == AppTier.SIMPLE
    NavEffects(nav, requestedTabRoute, onRequestedTabConsumed, inCall)

    CompositionLocalProvider(LocalTier provides tier) {
        ModalNavigationDrawer(
            drawerState = nav.drawerState,
            // Swiping right opens the drawer only on the chat; elsewhere the same swipe means "back". The
            // simplest tier has no drawer at all.
            gesturesEnabled = !simple && !inCall && (nav.drawerState.isOpen || !nav.covered),
            drawerContent = {
                AssistantDrawer(
                    roster = rememberRoster(assistantName, characterViewModel),
                    actions = drawerActions(nav, chatViewModel, conversations),
                    characterActions = characterActions(nav, characterViewModel),
                )
            },
        ) {
            // With the keyboard: the page ends where it begins.
            val insets = WindowInsets.safeDrawing
            val background = MaterialTheme.colorScheme.surface
            Scaffold(containerColor = background, contentWindowInsets = insets) { paddingValues ->
                // Messenger-style push / pop: the new page slides in over the chat, the chat drifts back;
                // returning reverses it. Every page paints an opaque background of its own, so the one
                // sliding underneath never shows through mid-way. The pages open in their final state.
                AnimatedContent(
                    targetState = nav.screenRoute,
                    transitionSpec = { ScreenTransitions.transform(isForward(initialState, targetState)) },
                    label = "screen",
                ) { route ->
                    val page =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(paddingValues)
                    when (route) {
                        TopLevelRoute.Settings.route -> {
                            SettingsHost(permissionRequests, nav, page.then(swipeBack), viewModel)
                        }

                        TopLevelRoute.Contact.route, TopLevelRoute.Group.route -> {
                            SidePage(route, nav.newGroup, nav::showChat, page.then(swipeBack))
                        }

                        else -> {
                            ChatPage(permissionRequests, nav, chatViewModel, callViewModel, page)
                        }
                    }
                }
                CallOverlay(visible = inCall, contentPadding = paddingValues, viewModel = callViewModel)
            }
        }
    }
    CharacterHost(characterViewModel, permissionRequests.mediaImages, mediaPermissionGrants)
    // Vault sentences and model-service set-up from any input (chat, typing, power-button wake)
    // are confirmed here, never sent to a model.
    VaultSavePrompt()
    ProviderSetupPrompt()
}

/** The chat itself, with everything it may ask of the host wired to navigation and permissions. */
@Composable
private fun ChatPage(
    permissionRequests: PermissionRequests,
    nav: MainNav,
    chatViewModel: NativeChatViewModel,
    callViewModel: CallViewModel,
    modifier: Modifier,
) {
    val focus = LocalFocusManager.current
    val tierViewModel: TierViewModel = hiltViewModel()
    val context = LocalContext.current
    // An answer's "used N memories" line opens the library on exactly those entries.
    val openMemories: (List<String>) -> Unit = { ids ->
        MemoryFocus.set(ids)
        nav.openSettings(SettingsRoute.MemoryLibrary)
    }
    CompositionLocalProvider(LocalMemoryOpener provides openMemories, LocalGroupOpener provides nav::showGroups) {
        ChatScreen(
            actions =
                ChatScreenActions(
                    onRequestMicrophonePermission = permissionRequests.microphone,
                    onRequestCameraPermission = permissionRequests.camera,
                    onOpenMenu = nav::openDrawer,
                    onSetupStep = { step -> SetupSteps.open(step, context) { nav.openSettings(it) } },
                    onTierChange = tierViewModel::set,
                    onStartCall = { video ->
                        if (video) permissionRequests.camera()
                        permissionRequests.microphone()
                        // The keyboard would otherwise pop up under the call.
                        focus.clearFocus()
                        callViewModel.start(video)
                    },
                ),
            modifier = modifier,
            viewModel = chatViewModel,
        )
    }
}

/** The drawer's character roster, with avatars converted for Compose once per change. */
@Composable
private fun rememberRoster(
    assistantName: String,
    characterViewModel: CharacterViewModel,
): DrawerCharacters {
    val characters by characterViewModel.characters.collectAsStateWithLifecycle()
    val activeId by characterViewModel.activeId.collectAsStateWithLifecycle()
    val avatars by characterViewModel.avatars.collectAsStateWithLifecycle()
    val images = remember(avatars) { avatars.mapValues { it.value.asImageBitmap() } }
    return DrawerCharacters(assistantName, characters, activeId, images)
}

/** Every character action closes the drawer first, then does its thing on the screen behind it. */
private fun characterActions(
    nav: MainNav,
    characterViewModel: CharacterViewModel,
): CharacterActions =
    CharacterActions(
        onSelect = { id ->
            nav.closeDrawer()
            characterViewModel.select(id)
        },
        onDelete = characterViewModel::delete,
        onImport = {
            nav.closeDrawer()
            characterViewModel.openImport()
        },
    )

/** The import sheet and the "已导入角色" toast; rescans as soon as the images permission is granted. */
@Composable
private fun CharacterHost(
    characterViewModel: CharacterViewModel,
    onRequestMediaPermission: () -> Unit,
    mediaPermissionGrants: Int,
) {
    val context = LocalContext.current
    val importState by characterViewModel.import.collectAsStateWithLifecycle()
    val notice by characterViewModel.notice.collectAsStateWithLifecycle()
    LaunchedEffect(mediaPermissionGrants) { if (mediaPermissionGrants > 0) characterViewModel.rescan() }
    LaunchedEffect(notice) {
        notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            characterViewModel.noticeShown()
        }
    }
    CharacterImportSheet(
        state = importState,
        actions =
            ImportActions(
                onClose = characterViewModel::closeImport,
                onRequestPermission = onRequestMediaPermission,
                onRescan = characterViewModel::rescan,
                onImport = characterViewModel::importDiscovered,
            ),
    )
}

@Composable
private fun SettingsHost(
    permissionRequests: PermissionRequests,
    nav: MainNav,
    modifier: Modifier,
    viewModel: MainViewModel,
) {
    SettingsScreen(
        onRequestNotificationPermission = permissionRequests.notification,
        onRequestCameraPermission = permissionRequests.camera,
        onRequestMicrophonePermission = permissionRequests.microphone,
        onRequestLocationPermission = permissionRequests.location,
        pendingRoute = nav.pendingSettingsRoute,
        onPendingRouteConsumed = { nav.pendingSettingsRoute = null },
        onClose = nav::showChat,
        modifier = modifier,
        viewModel = viewModel,
    )
}

/** Every drawer action closes the drawer first, then does its thing on the screen behind it. */
private fun drawerActions(
    nav: MainNav,
    chatViewModel: NativeChatViewModel,
    conversations: ConversationsViewModel,
): DrawerActions =
    DrawerActions(
        onNewConversation = {
            nav.closeDrawer()
            conversations.startNew()
        },
        onConversations = {
            nav.closeDrawer()
            conversations.show()
        },
        onClear = {
            nav.closeDrawer()
            chatViewModel.clearConversation()
        },
        onOpenSettings = {
            nav.closeDrawer()
            nav.showSettings()
        },
        onOpenContact = {
            nav.closeDrawer()
            nav.showContact()
        },
    )
