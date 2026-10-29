@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.AppTier
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatHttpService
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatOutline
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpChatLogic
import com.danielealbano.androidremotecontrolmcp.services.chat.Presence
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkState
import com.danielealbano.androidremotecontrolmcp.ui.LocalTier
import com.danielealbano.androidremotecontrolmcp.ui.chat.AttachActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.CallOptionsSheet
import com.danielealbano.androidremotecontrolmcp.ui.chat.CameraShots
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatComposer
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatPresentation
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatScreenActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatSearch
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatSearchBar
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatViewState
import com.danielealbano.androidremotecontrolmcp.ui.chat.ComposerActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.ComposerState
import com.danielealbano.androidremotecontrolmcp.ui.chat.ConversationsHost
import com.danielealbano.androidremotecontrolmcp.ui.chat.ErrorNotice
import com.danielealbano.androidremotecontrolmcp.ui.chat.GroupsButton
import com.danielealbano.androidremotecontrolmcp.ui.chat.InteractionState
import com.danielealbano.androidremotecontrolmcp.ui.chat.MessageInteractionHost
import com.danielealbano.androidremotecontrolmcp.ui.chat.MessageListState
import com.danielealbano.androidremotecontrolmcp.ui.chat.MicActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.OutlineSheet
import com.danielealbano.androidremotecontrolmcp.ui.chat.RemotePermissionCards
import com.danielealbano.androidremotecontrolmcp.ui.chat.SelectionActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.SelectionBar
import com.danielealbano.androidremotecontrolmcp.ui.chat.SetupBanner
import com.danielealbano.androidremotecontrolmcp.ui.chat.SkillActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.Speaker
import com.danielealbano.androidremotecontrolmcp.ui.chat.rememberChatViewState
import com.danielealbano.androidremotecontrolmcp.ui.chat.rememberInteractionState
import com.danielealbano.androidremotecontrolmcp.ui.components.AssistantAvatar
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.TierUpgradeDialog
import com.danielealbano.androidremotecontrolmcp.ui.theme.PresenceBlocked
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AssistViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.DictationState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.DictationViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MessageAction
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.NativeChatUiState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.NativeChatViewModel
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import kotlinx.coroutines.launch

/** The two view models the conversation pane drives, plus the host's microphone prompt. */
private class ChatModels(
    val chat: NativeChatViewModel,
    val dictation: DictationViewModel,
    val onMicPermissionNeeded: () -> Unit,
    val onCameraPermissionNeeded: () -> Unit,
)

@Composable
fun ChatScreen(
    actions: ChatScreenActions,
    modifier: Modifier = Modifier,
    viewModel: NativeChatViewModel = hiltViewModel(),
    dictation: DictationViewModel = hiltViewModel(),
    assistViewModel: AssistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val assistantName by assistViewModel.assistantName.collectAsStateWithLifecycle()
    // With a character active, the header and every assistant row wear its name and portrait.
    val avatar = remember(ui.avatar) { ui.avatar?.asImageBitmap() }
    val speaker = Speaker(ui.character?.name ?: assistantName, avatar)
    val models =
        remember(viewModel, dictation) {
            ChatModels(viewModel, dictation, actions.onRequestMicrophonePermission, actions.onRequestCameraPermission)
        }
    val view = rememberChatViewState()
    val interactions = rememberInteractionState()
    val items = remember(ui.messages) { ChatPresentation.toItems(ui.messages) }
    val hits = remember(items, view.activeQuery) { ChatSearch.hits(items, view.activeQuery) }
    BackHandler(enabled = view.searching) { view.closeSearch() }

    LaunchedEffect(Unit) { ChatHttpService.start(context.applicationContext) }
    // Every return to the chat: the user may be back from switching on a setup step elsewhere.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshStatus()
        onPauseOrDispose { }
    }

    var callOptions by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        if (view.searching) {
            ChatSearchBar(view, hits)
        } else {
            ChatHeader(ui = ui, speaker = speaker, actions = actions, view = view, onCall = { callOptions = true })
        }
        // Setting up this phone's own helpers has nothing to do with talking to an agent on a computer.
        if (ui.remote == null) SetupBanner(ui.setup, actions.onSetupStep)
        val list = MessageListState(ui, speaker, items, view, interactions)
        ConversationPane(list, models, modifier = Modifier.weight(1f))
    }
    // A search result in another conversation: once that conversation is shown, go to the message.
    LaunchedEffect(ui.pendingJump, items) {
        val wanted = ui.pendingJump
        if (wanted != null && items.any { it.messageId == wanted }) {
            view.jumpTo(wanted)
            viewModel.act(MessageAction.JumpDone)
        }
    }
    ConversationsHost(speaker.name)
    if (view.outline) {
        OutlineSheet(
            entries = remember(ui.messages) { ChatOutline.entries(ui.messages) },
            onPick = { id ->
                view.outline = false
                view.jumpTo(id)
            },
            onDismiss = { view.outline = false },
        )
    }
    if (callOptions) {
        CallOptionsSheet(
            onPick = { video ->
                callOptions = false
                actions.onStartCall(video)
            },
            onDismiss = { callOptions = false },
        )
    }
}

@Composable
private fun ChatHeader(
    ui: NativeChatUiState,
    speaker: Speaker,
    actions: ChatScreenActions,
    view: ChatViewState,
    onCall: () -> Unit,
) {
    // The avatar is the app's menu button: one tap opens the drawer with everything else in it. In the
    // simplest tier there is no menu: a long press asks whether to show more (the family's way in).
    val tier = LocalTier.current
    val simple = tier == AppTier.SIMPLE
    var askUp by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val menuLabel = stringResource(R.string.chat_open_menu)
        val entryLabel = stringResource(R.string.tier_entry_label)
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = if (simple) null else menuLabel,
                        onClick = if (simple) ({}) else actions.onOpenMenu,
                        onLongClickLabel = if (simple) entryLabel else null,
                        onLongClick = if (simple) ({ askUp = true }) else null,
                    ).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { HeaderTitle(ui, speaker) }
        // Finding one's way around: a word in this conversation, or the list of questions asked.
        if (!simple) {
            IconButton(onClick = view::openSearch) {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.chat_search))
            }
            IconButton(onClick = { view.outline = true }) {
                Icon(Icons.AutoMirrored.Filled.Toc, contentDescription = stringResource(R.string.chat_outline))
            }
        }
        // A call with this contact (voice or video). The contact's page is behind the avatar: the
        // drawer's top block opens it.
        IconButton(onClick = onCall) {
            Icon(Icons.Default.Call, contentDescription = stringResource(R.string.chat_call))
        }
        // Group chats are agents talking to each other: the developer's.
        if (tier.atLeast(AppTier.DEVELOPER)) GroupsButton()
    }
    if (askUp) {
        TierUpgradeDialog(
            onConfirm = {
                askUp = false
                actions.onTierChange(AppTier.OFFICE)
            },
            onDismiss = { askUp = false },
        )
    }
}

@Composable
private fun ConversationPane(
    list: MessageListState,
    models: ChatModels,
    modifier: Modifier = Modifier,
) {
    val ui = list.ui
    val interactions = list.interactions
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val dictationState by models.dictation.state.collectAsStateWithLifecycle()
    var voiceMode by remember { mutableStateOf(false) }
    val skillHost = rememberSkillHost()
    val picked = interactions.selected
    BackHandler(enabled = picked != null) { interactions.selected = null }

    // Dictated text goes into the draft, then the keyboard comes back so it can be edited or sent.
    LaunchedEffect(dictationState.spoken) {
        val spoken = dictationState.spoken ?: return@LaunchedEffect
        models.chat.setDraft(if (ui.draft.isBlank()) spoken else "${ui.draft.trimEnd()} $spoken")
        models.dictation.spokenConsumed()
        voiceMode = false
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(modifier = modifier.fillMaxSize()) {
        (ui.error ?: dictationState.error)?.let {
            ErrorNotice(text = it, onDismiss = {
                models.chat.dismissError()
                models.dictation.dismissError()
            })
        }
        MessageList(list, models.chat, modifier = Modifier.weight(1f))
        ui.remote?.permissions?.takeIf { it.isNotEmpty() }?.let { asked ->
            RemotePermissionCards(asked) { pending, reply ->
                models.chat.act(MessageAction.AnswerPermission(pending, reply))
            }
        }
        if (picked != null) {
            // Picking several messages: the composer gives way to what can be done with them.
            SelectionBar(picked.size, selectionActions(picked, interactions, models.chat))
        } else {
            ChatComposer(
                state = composerState(ui, dictationState, voiceMode),
                actions =
                    ComposerActions(
                        onDraftChange = models.chat::setDraft,
                        onSend = models.chat::send,
                        onStop = models.chat::stop,
                        mic = micActions(models, dictationState, keyboard, voiceMode) { voiceMode = it },
                        attach = attachActions(models, skillHost),
                    ),
                focusRequester = focusRequester,
            )
        }
    }
    MessageInteractionHost(interactions, ui.forwardTargets, models.chat)
    SkillHost(skillHost, ui, models.chat)
}

/** Leaving the picked messages out of the model's context, or putting them back; either ends the picking. */
private fun selectionActions(
    picked: Set<String>,
    interactions: InteractionState,
    chat: NativeChatViewModel,
): SelectionActions {
    val mark: (Boolean) -> Unit = { excluded ->
        chat.act(MessageAction.SetExcluded(picked, excluded))
        interactions.selected = null
    }
    return SelectionActions(
        onExclude = { mark(true) },
        onInclude = { mark(false) },
        onCancel = { interactions.selected = null },
    )
}

private fun composerState(
    ui: NativeChatUiState,
    dictation: DictationState,
    voiceMode: Boolean,
): ComposerState {
    val textReady = PhoneMcpChatLogic.canSendDraft(ui.draft, ui.sending, dictation.listening)
    val attachmentReady = ui.attachment != null && !ui.sending && !ui.attaching
    return ComposerState(
        draft = ui.draft,
        canSend = textReady || attachmentReady,
        sending = ui.sending,
        stoppable = ui.streaming != null,
        listening = dictation.listening,
        cancelHint = dictation.cancelHint,
        voiceMode = voiceMode,
        replyTo = ui.replyTo,
        attachment = ui.attachment,
        attaching = ui.attaching,
        contextUsage = ui.contextUsage,
        recording = ui.skill.recording,
        playing = ui.skill.playing,
        taskRunning = ui.tasks.values.any { !it.status.finished },
        focusLabel = focusLabel(ui),
    )
}

@Composable
private fun micActions(
    models: ChatModels,
    dictation: DictationState,
    keyboard: SoftwareKeyboardController?,
    voiceMode: Boolean,
    setVoiceMode: (Boolean) -> Unit,
): MicActions {
    val context = LocalContext.current
    return MicActions(
        onDown = {
            val granted = PermissionUtils.isMicrophonePermissionGranted(context)
            if (granted) models.dictation.begin() else models.onMicPermissionNeeded()
            granted
        },
        onUp = models.dictation::end,
        onCancelHint = models.dictation::setCancelHint,
        onToggleVoiceMode = {
            if (dictation.listening) models.dictation.end(false)
            setVoiceMode(!voiceMode)
            if (!voiceMode) keyboard?.hide()
        },
    )
}

/** Pickers for a picture (photo picker), a fresh photo (the camera app) or any document (system file picker). */
@Composable
private fun attachActions(
    models: ChatModels,
    host: SkillHostState,
): AttachActions {
    val viewModel = models.chat
    val context = LocalContext.current
    // The camera app writes into the chat's own attachment folder; the copy the chat keeps is made from there.
    val shot = remember(context) { CameraShots.target(context) }
    val takePhoto =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            if (taken) viewModel.act(MessageAction.Attach(shot, image = true))
        }
    val pickImage =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { viewModel.act(MessageAction.Attach(it, image = true)) }
        }
    val pickFile =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.act(MessageAction.Attach(it, image = false)) }
        }
    return AttachActions(
        onPickImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onTakePhoto = {
            // The app declares the camera permission, so even another app's camera needs it granted.
            val granted = PermissionUtils.isCameraPermissionGranted(context)
            if (granted) takePhoto.launch(shot) else models.onCameraPermissionNeeded()
        },
        onPickFile = { pickFile.launch(arrayOf("*/*")) },
        onRemoveAttachment = { viewModel.act(MessageAction.RemoveAttachment) },
        onClearReply = { viewModel.act(MessageAction.ClearReply) },
        skill =
            SkillActions(
                onRecord = { host.recordDialog = true },
                onPlay = { host.playSheet = true },
                onStopRecording = viewModel.skills::stopRecording,
                onClearFocus = { viewModel.skills.review(viewModel.ui.value.skill.reviewing) },
            ),
    )
}
