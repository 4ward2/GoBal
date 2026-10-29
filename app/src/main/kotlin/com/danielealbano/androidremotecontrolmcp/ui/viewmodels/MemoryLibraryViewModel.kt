package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.chat.ContextBudget
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationStore
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryEdit
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryEntry
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryLibrary
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryRepository
import com.danielealbano.androidremotecontrolmcp.services.memory.MemorySelector
import com.danielealbano.androidremotecontrolmcp.services.memory.MemorySource
import com.danielealbano.androidremotecontrolmcp.services.memory.NewMemory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A memory as the library page lists it. */
data class MemoryRow(
    val entry: MemoryEntry,
    /** How it begins, for a line under the title. */
    val preview: String,
    /** About how much of a model's context it takes when sent whole. */
    val tokens: Int,
    /** "所有联系人" or the contact's name. */
    val scopeName: String,
)

/** A memory open for writing or correcting; [id] is null for a new one. */
data class MemoryEditing(
    val id: String?,
    val text: String,
    /** True when every contact gets it, false when only the current one does. */
    val global: Boolean,
)

data class MemoryLibraryUiState(
    val rows: List<MemoryRow> = emptyList(),
    val query: String = "",
    /** When set, only these entries are listed: the ones an answer in the chat used. */
    val focus: Set<String> = emptySet(),
    val selected: Set<String> = emptySet(),
    val editing: MemoryEditing? = null,
    /** Who the chat is with right now; a new memory "for this contact only" is theirs. */
    val contactName: String = "",
    /** How many tokens of memory a request to the current contact's model may carry. */
    val budget: Int = 0,
    val notice: String? = null,
) {
    /** What the page shows: the focused ones, or what matches the search. */
    val shown: List<MemoryRow>
        get() {
            val words = query.trim()
            val inFocus = if (focus.isEmpty()) rows else rows.filter { it.entry.id in focus }
            val hit: (MemoryRow) -> Boolean = { row ->
                row.entry.title.contains(words, true) || row.preview.contains(words, true)
            }
            return if (words.isEmpty()) inFocus else inFocus.filter(hit)
        }

    /** The picked entries, in the order they are listed. */
    fun picked(): List<String> = shown.map { it.entry.id }.filter { it in selected }

    /** What all enabled memories would take if every one were sent. */
    val enabledTokens: Int get() = rows.filter { it.entry.enabled }.sumOf { it.tokens }
}

/** One thing the user did on the library page. */
sealed interface MemoryIntent {
    data class Search(
        val query: String,
    ) : MemoryIntent

    /** Open an entry to correct it, or with a null id start a new one. */
    data class Open(
        val id: String?,
    ) : MemoryIntent

    /** Close the open entry; [text] null leaves it as it was. */
    data class Close(
        val text: String?,
        val global: Boolean,
    ) : MemoryIntent

    data class Enable(
        val id: String,
        val enabled: Boolean,
    ) : MemoryIntent

    data class Select(
        val id: String,
    ) : MemoryIntent

    data object ClearSelection : MemoryIntent

    data object MergeSelected : MemoryIntent

    data object DeleteSelected : MemoryIntent

    data object ShowAll : MemoryIntent

    data object NoticeShown : MemoryIntent
}

/** The memory library page: every entry, readable and correctable, with what it costs. */
@HiltViewModel
class MemoryLibraryViewModel
    @Inject
    constructor(
        private val library: MemoryLibrary,
        private val store: ConversationStore,
        private val settings: AssistSettingsRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _ui = MutableStateFlow(MemoryLibraryUiState(focus = MemoryFocus.take()))
        val ui: StateFlow<MemoryLibraryUiState> = _ui.asStateFlow()
        private var owner = MemoryRepository.ASSISTANT

        init {
            viewModelScope.launch(ioDispatcher) {
                describeContact()
                library.load()
                library.entries.collect { entries -> _ui.update { it.copy(rows = entries.map { e -> rowOf(e) }) } }
            }
        }

        private suspend fun describeContact() {
            val card = store.activeCharacter()
            owner = card?.id ?: MemoryRepository.ASSISTANT
            val choice = store.modelChoice()
            val provider = settings.providers.first().firstOrNull { it.id == choice.providerId }
            val budget = MemorySelector.budget(ContextBudget.window(provider, choice.model))
            val name = card?.name ?: settings.assistantName.first()
            _ui.update { it.copy(contactName = name, budget = budget) }
        }

        private suspend fun rowOf(entry: MemoryEntry): MemoryRow {
            val text = library.text(entry.id)
            val scope = if (entry.scope == MemoryEntry.GLOBAL) EVERYONE else nameOf(entry.scope)
            return MemoryRow(entry, text.trim().take(PREVIEW_CHARS), ContextBudget.tokens(text), scope)
        }

        private suspend fun nameOf(scope: String): String {
            val known = if (scope == owner) _ui.value.contactName else ""
            val assistant = scope == MemoryRepository.ASSISTANT
            return known.ifEmpty { if (assistant) settings.assistantName.first() else ONE_CONTACT }
        }

        fun on(intent: MemoryIntent) {
            when (intent) {
                is MemoryIntent.Search -> _ui.update { it.copy(query = intent.query) }
                is MemoryIntent.Select -> _ui.update { it.copy(selected = it.selected.flipped(intent.id)) }
                MemoryIntent.ClearSelection -> _ui.update { it.copy(selected = emptySet()) }
                MemoryIntent.ShowAll -> _ui.update { it.copy(focus = emptySet()) }
                MemoryIntent.NoticeShown -> _ui.update { it.copy(notice = null) }
                else -> viewModelScope.launch(ioDispatcher) { write(intent) }
            }
        }

        private suspend fun write(intent: MemoryIntent) {
            val state = _ui.value
            val failed: Throwable? =
                when (intent) {
                    is MemoryIntent.Open -> null.also { open(intent.id) }
                    is MemoryIntent.Close -> close(state.editing, intent)
                    is MemoryIntent.Enable -> enable(intent)
                    MemoryIntent.MergeSelected -> library.merge(state.picked()).exceptionOrNull()
                    MemoryIntent.DeleteSelected -> null.also { library.remove(state.selected) }
                    else -> null
                }
            val done = intent is MemoryIntent.MergeSelected || intent is MemoryIntent.DeleteSelected
            val cleared = done && failed == null
            _ui.update { it.copy(notice = failed?.message, selected = if (cleared) emptySet() else it.selected) }
        }

        private suspend fun enable(intent: MemoryIntent.Enable): Throwable? {
            val done = library.edit(intent.id, MemoryEdit(enabled = intent.enabled))
            return done.exceptionOrNull()
        }

        private suspend fun open(id: String?) {
            val entry =
                _ui.value.rows
                    .firstOrNull { it.entry.id == id }
                    ?.entry
            val text = entry?.let { library.text(it.id) }.orEmpty()
            val global = entry == null || entry.scope == MemoryEntry.GLOBAL
            _ui.update { it.copy(editing = MemoryEditing(entry?.id, text, global)) }
        }

        /** Saves what the dialog holds: a new memory, or the corrected text and reach of an old one. */
        private suspend fun close(
            editing: MemoryEditing?,
            intent: MemoryIntent.Close,
        ): Throwable? {
            _ui.update { it.copy(editing = null) }
            val text = intent.text?.trim().orEmpty()
            // "Only this contact" keeps a memory with the contact it already belongs to.
            val old =
                _ui.value.rows
                    .firstOrNull { it.entry.id == editing?.id }
                    ?.entry
                    ?.scope
            val one = old?.takeIf { it != MemoryEntry.GLOBAL } ?: owner
            val scope = if (intent.global) MemoryEntry.GLOBAL else one
            val changed = MemoryEdit(text.takeIf { it != editing?.text }, scope)
            val fresh = NewMemory(text, scope = scope, source = MemorySource(MemorySource.MANUAL))
            val saved =
                when {
                    editing == null || text.isEmpty() -> null
                    editing.id == null -> library.add(fresh)
                    else -> library.edit(editing.id, changed)
                }
            return saved?.exceptionOrNull()
        }

        private fun Set<String>.flipped(id: String): Set<String> = if (id in this) this - id else this + id

        private companion object {
            const val EVERYONE = "所有联系人"
            const val ONE_CONTACT = "某个联系人"
            const val PREVIEW_CHARS = 120
        }
    }

/** Which memories the library page should show when it is opened from an answer in the chat. Handed out once. */
object MemoryFocus {
    @Volatile
    private var ids: Set<String> = emptySet()

    fun set(wanted: Collection<String>) {
        ids = wanted.toSet()
    }

    fun take(): Set<String> = ids.also { ids = emptySet() }
}
