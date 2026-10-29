package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationExporter
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationFiles
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationHit
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationMeta
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationRef
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ConversationsUiState(
    /** True while the list of conversations is shown over the chat. */
    val open: Boolean = false,
    /** The current contact's conversations: pinned first, then the most recently used. */
    val conversations: List<ConversationMeta> = emptyList(),
    val activeId: String = "",
    val query: String = "",
    /** Messages matching [query] in every contact's conversations. */
    val hits: List<ConversationHit> = emptyList(),
    /** Who each owner in [hits] is, for the "小夏 · 标题" line. */
    val names: Map<String, String> = emptyMap(),
)

/** The list of a contact's conversations, and the search across every contact's. */
@HiltViewModel
class ConversationsViewModel
    @Inject
    constructor(
        private val store: ConversationStore,
        private val characters: CharacterRepository,
        private val assistSettings: AssistSettingsRepository,
        private val exporter: ConversationExporter,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _ui = MutableStateFlow(ConversationsUiState())
        val ui: StateFlow<ConversationsUiState> = _ui.asStateFlow()
        private val catalog = store.catalog
        private var searching: Job? = null
        private val _exports = MutableSharedFlow<File>(extraBufferCapacity = 1)

        /** A conversation written out as a file, ready to be handed to another app. */
        val exports: SharedFlow<File> = _exports.asSharedFlow()

        fun show() {
            _ui.update { it.copy(open = true, query = "", hits = emptyList()) }
            viewModelScope.launch(ioDispatcher) { refresh() }
        }

        fun hide() {
            _ui.update { it.copy(open = false) }
        }

        /** Starts (or returns to) an empty conversation with the current contact. */
        fun startNew() {
            viewModelScope.launch(ioDispatcher) {
                catalog.create(store.owner())
                _ui.update { it.copy(open = false) }
            }
        }

        fun open(id: String) {
            viewModelScope.launch(ioDispatcher) {
                catalog.open(store.owner(), id)
                _ui.update { it.copy(open = false) }
            }
        }

        /** Goes to the message a search found: its contact, its conversation, then the message. */
        fun openHit(hit: ConversationHit) {
            viewModelScope.launch(ioDispatcher) {
                characters.setActive(hit.owner.takeIf { it != ConversationFiles.ASSISTANT })
                catalog.open(hit.owner, hit.conversationId, jumpTo = hit.messageId)
                _ui.update { it.copy(open = false) }
            }
        }

        /** Names, pins or deletes one of the current contact's conversations, then shows the list anew. */
        fun change(change: ConversationChange) {
            viewModelScope.launch(ioDispatcher) {
                val owner = store.owner()
                when (change) {
                    is ConversationChange.Rename -> catalog.rename(owner, change.id, change.title)
                    is ConversationChange.Pin -> catalog.setPinned(owner, change.id, change.pinned)
                    is ConversationChange.Delete -> catalog.delete(owner, change.id)
                }
                refresh()
            }
        }

        /** Writes the conversation out as a Markdown file and offers it on [exports]. */
        fun export(meta: ConversationMeta) {
            viewModelScope.launch(ioDispatcher) {
                val owner = store.owner()
                val contact = namesOf(setOf(owner))[owner].orEmpty()
                runCatching { exporter.export(ConversationRef(owner, meta.id), meta.title, contact) }
                    .onSuccess { _exports.emit(it) }
            }
        }

        /** Searches every conversation for [query] once typing pauses. */
        fun search(query: String) {
            _ui.update { it.copy(query = query) }
            searching?.cancel()
            searching =
                viewModelScope.launch(ioDispatcher) {
                    delay(TYPING_PAUSE_MS)
                    val hits = catalog.search(query)
                    val names = namesOf(hits.map { it.owner }.toSet())
                    _ui.update { if (it.query == query) it.copy(hits = hits, names = names) else it }
                }
        }

        private suspend fun namesOf(owners: Set<String>): Map<String, String> {
            characters.load()
            val cards = characters.characters.value.associate { it.id to it.name }
            val assistant = assistSettings.assistantName.first()
            return owners.associateWith { if (it == ConversationFiles.ASSISTANT) assistant else cards[it].orEmpty() }
        }

        private suspend fun refresh() {
            val owner = store.owner()
            val list = catalog.list(owner)
            val active = catalog.activeId(owner)
            _ui.update { it.copy(conversations = list, activeId = active) }
        }

        private companion object {
            const val TYPING_PAUSE_MS = 250L
        }
    }

/** An edit to one conversation in the list. */
sealed interface ConversationChange {
    val id: String

    data class Rename(
        override val id: String,
        val title: String,
    ) : ConversationChange

    data class Pin(
        override val id: String,
        val pinned: Boolean,
    ) : ConversationChange

    data class Delete(
        override val id: String,
    ) : ConversationChange
}
