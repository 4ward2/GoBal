package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRun
import com.danielealbano.androidremotecontrolmcp.services.assistant.ChatRouting
import com.danielealbano.androidremotecontrolmcp.services.assistant.SetupState
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterCard
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.chat.Attachment
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBackend
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBubble
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationRef
import com.danielealbano.androidremotecontrolmcp.services.chat.ForwardTarget
import com.danielealbano.androidremotecontrolmcp.services.chat.LoopbackStatus
import com.danielealbano.androidremotecontrolmcp.services.chat.Presence
import com.danielealbano.androidremotecontrolmcp.services.chat.QuotedMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class NativeChatUiState(
    val messages: List<ChatBubble> = emptyList(),
    val draft: String = "",
    val sending: Boolean = false,
    /**
     * The model's reply as far as it has been written: null when no reply is streaming, "" while
     * waiting for its first words. While non-null the composer offers "stop".
     */
    val streaming: String? = null,
    val error: String? = null,
    val status: LoopbackStatus = LoopbackStatus(),
    /** The role-play character being talked to, or null for the plain assistant. */
    val character: CharacterCard? = null,
    /** The character's portrait, decoded once per switch so list rows never decode. */
    val avatar: Bitmap? = null,
    /** The message the next send answers, shown above the composer until sent or cleared. */
    val replyTo: QuotedMessage? = null,
    /** A picture or document waiting in the composer to be sent with the next message. */
    val attachment: Attachment? = null,
    /** True while a picked file is being copied and read. */
    val attaching: Boolean = false,
    /** Conversations a message can be forwarded to (everyone but the current contact). */
    val forwardTargets: List<ForwardTarget> = emptyList(),
    /** A message to flash briefly after jumping to it from a quote. */
    val highlightId: String? = null,
    /** The conversation [messages] belong to; everything saved from this state goes there. */
    val conversation: ConversationRef? = null,
    /** Set when the contact is an agent on a paired computer: the link and what it asks leave for. */
    val remote: RemoteView? = null,
    /** The message being read aloud, if any. */
    val readingId: String? = null,
    /** A message to scroll to once this conversation is on screen (opened from a search result). */
    val pendingJump: String? = null,
    /** Liveness of the model API this contact talks through; drives the dot under the avatar. */
    val presence: Presence = Presence.CHECKING,
    /** Estimated share of the model's context window the next request will use, 0..1. */
    val contextUsage: Float = 0f,
    /** What is still to be set up before the assistant can talk, act and listen. */
    val setup: SetupState = SetupState(),
    /** The phone tasks the conversation's task cards show, by task id: running ones are live. */
    val tasks: Map<String, AgentRun> = emptyMap(),
    /** Recording, reviewing and replaying skills (see [SkillChat]). */
    val skill: SkillChatState = SkillChatState(),
)

@HiltViewModel
class NativeChatViewModel
    @Inject
    constructor(
        private val backend: ChatBackend,
        private val characters: CharacterRepository,
        private val routing: ChatRouting,
        private val assistSettings: AssistSettingsRepository,
        skillDeps: SkillChatDeps,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _ui = MutableStateFlow(NativeChatUiState())
        val ui: StateFlow<NativeChatUiState> = _ui.asStateFlow()

        // Declared before init: the collectors launched there may run on another thread at once and
        // reach refreshPresence() before a later-declared property would have been assigned.
        private val context = ContextUsageTracker(backend, assistSettings, _ui)
        private val remoteSync = RemoteChatSync(backend, routing.remote, _ui)

        /** Recording, reviewing and replaying skills from the chat. */
        val skills = SkillChat(backend, skillDeps, _ui, viewModelScope, ioDispatcher)

        private val turns =
            ChatTurns(TurnDeps(backend, routing, skills, context), _ui, viewModelScope, ioDispatcher)

        private val actions =
            ChatActionHandler(backend, _ui, viewModelScope, ioDispatcher) { assistSettings.assistantName.first() }
                .also {
                    it.turns = turns
                    it.agent = routing.agent
                    it.reader = routing.reader
                    it.remote = remoteSync
                    it.memory = routing.memory
                    it.onContextChanged = context::update
                }

        private val drafts = DraftSaver(backend.store, viewModelScope, ioDispatcher)

        /** Two switches in quick succession must not interleave their loading. */
        private val switching = Mutex()

        init {
            // Switching character switches the whole conversation: history, greeting, system prompt.
            viewModelScope.launch(ioDispatcher) {
                characters.load()
                characters.activeId.collect { showActiveConversation() }
            }
            viewModelScope.launch(ioDispatcher) {
                characters.characters.collect { cards ->
                    val active = _ui.value.character?.id
                    val targets = backend.editor.targets(active, assistSettings.assistantName.first())
                    _ui.update { state ->
                        state.copy(character = cards.firstOrNull { it.id == active }, forwardTargets = targets)
                    }
                    refreshPresence()
                }
            }
            // Every API is probed once at start-up; afterwards each reply or error keeps the dot current.
            viewModelScope.launch(ioDispatcher) { backend.presence.checkAllOnce() }
            viewModelScope.launch(ioDispatcher) { backend.presence.state.collect { refreshPresence() } }
            // A provider's context window can be edited in Settings while this chat stays alive.
            viewModelScope.launch(ioDispatcher) { assistSettings.providers.collect { context.refresh() } }
            // Another conversation of this contact was opened, started or took a deleted one's place.
            val opened = backend.store.catalog.revision
            viewModelScope.launch(ioDispatcher) { opened.drop(1).collect { showActiveConversation() } }
            skills.observe()
            TaskWatcher(backend, routing.agent, _ui).observe(viewModelScope, ioDispatcher)
            remoteSync.observe(viewModelScope, ioDispatcher)
            viewModelScope.launch(ioDispatcher) {
                routing.reader.state.collect { r ->
                    _ui.update { it.copy(readingId = r.messageId, error = r.error ?: it.error) }
                }
            }
        }

        private suspend fun refreshPresence() {
            val choice = backend.store.modelChoice()
            val presence = backend.presence.presenceOf(choice)
            val setup = routing.setup.check(choice, _ui.value.status.provider.configured)
            _ui.update { it.copy(presence = presence, setup = setup) }
            context.refresh()
        }

        private suspend fun showActiveConversation() =
            switching.withLock {
                turns.abandon()
                loadActiveConversation()
            }

        private suspend fun loadActiveConversation() {
            val card = backend.store.activeCharacter()
            val at = backend.store.here()
            val messages = backend.store.loadAt(at)
            val avatarFile = card?.takeIf { it.hasAvatar }?.let { characters.avatarFile(it.id) }
            val avatar = avatarFile?.let { BitmapFactory.decodeFile(it.path) }
            val targets = backend.editor.targets(card?.id, assistSettings.assistantName.first())
            val draft = backend.store.drafts.load(backend.store.draftKey())
            _ui.update {
                it.copy(
                    messages = messages,
                    conversation = at,
                    draft = draft,
                    character = card,
                    avatar = avatar,
                    error = null,
                    replyTo = null,
                    forwardTargets = targets,
                    // Only ever set here: the screen clears it once it has scrolled there.
                    pendingJump = backend.store.catalog.takeJump() ?: it.pendingJump,
                )
            }
            refreshPresence()
            viewModelScope.launch(ioDispatcher) { remoteSync.opened(at, viewModelScope) }
        }

        /** Empties the conversation (persisted history included). Ignored while a reply is pending. */
        fun clearConversation() {
            if (_ui.value.sending) return
            viewModelScope.launch(ioDispatcher) {
                val fresh = backend.store.clear()
                _ui.update { it.copy(messages = fresh, error = null, replyTo = null) }
                context.update()
            }
        }

        fun setDraft(value: String) {
            _ui.update { it.copy(draft = value, error = null) }
            context.update()
            drafts.changed(value)
        }

        fun dismissError() {
            _ui.update { it.copy(error = null) }
        }

        fun refreshStatus() {
            viewModelScope.launch(ioDispatcher) {
                val status = runCatching { backend.loopback.status() }.getOrDefault(LoopbackStatus())
                _ui.update { it.copy(status = status) }
                // Also re-checks the setup steps: the user may be back from switching one on.
                refreshPresence()
            }
        }

        /** Reply / forward / delete / attach, from the long-press menu and the composer. */
        fun act(action: MessageAction) = actions.handle(action)

        /** Sends the composer's text (see [ChatTurns.send]); the saved draft goes with it. */
        fun send() {
            // Judged before sending: the composer is emptied a moment later, on another thread.
            val before = _ui.value
            val idle = !before.sending && !before.attaching
            val goes = idle && (before.draft.isNotBlank() || before.attachment != null)
            turns.send()
            if (goes) drafts.sent()
        }

        /** Stops the reply being written, keeping what has arrived. */
        fun stop() = turns.stop()
    }
