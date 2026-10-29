package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.AssistantProfile
import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterCard
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.memory.ContentNames
import com.danielealbano.androidremotecontrolmcp.services.memory.DocumentReader
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryFile
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryNotes
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The editable texts of a contact page; the same shape for a character and for the assistant. */
data class ContactForm(
    val name: String = "",
    val description: String = "",
    val backstory: String = "",
    val model: ModelChoice = ModelChoice(),
)

/** A memory file open for reading and correcting; [id] is null for a note that is being written. */
data class MemoryDraft(
    val id: String?,
    val name: String,
    val text: String,
)

data class ContactUiState(
    /** Null when the page is about the plain assistant. */
    val character: CharacterCard? = null,
    val avatar: Bitmap? = null,
    val form: ContactForm = ContactForm(),
    val providers: List<ModelProvider> = emptyList(),
    val memory: List<MemoryFile> = emptyList(),
    val reading: Boolean = false,
    val notice: String? = null,
    val editingMemory: MemoryDraft? = null,
)

/**
 * The contact page for the active conversation: who it is (editable), which model it talks
 * through (from 设置 → 服务商) and the memory files fed into its prompt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ContactViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val characters: CharacterRepository,
        private val settings: AssistSettingsRepository,
        private val memory: MemoryRepository,
        private val reader: DocumentReader,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _ui = MutableStateFlow(ContactUiState())
        val ui: StateFlow<ContactUiState> = _ui.asStateFlow()

        private val owner: String get() = _ui.value.character?.id ?: MemoryRepository.ASSISTANT

        init {
            viewModelScope.launch(ioDispatcher) {
                characters.load()
                combine(characters.activeId, characters.characters) { id, cards -> cards.firstOrNull { it.id == id } }
                    .collect { card -> showContact(card) }
            }
            viewModelScope.launch(ioDispatcher) {
                settings.providers.collect { p -> _ui.update { it.copy(providers = p) } }
            }
            viewModelScope.launch(ioDispatcher) {
                characters.activeId
                    .flatMapLatest { id -> memory.files(id ?: MemoryRepository.ASSISTANT) }
                    .collect { files -> _ui.update { it.copy(memory = files) } }
            }
        }

        private suspend fun showContact(card: CharacterCard?) {
            val form =
                if (card != null) {
                    ContactForm(card.name, card.description, card.backstory, card.model)
                } else {
                    val profile = settings.assistantProfile.first()
                    val model = settings.assistantModel.first()
                    ContactForm(settings.assistantName.first(), profile.description, profile.backstory, model)
                }
            val avatarFile = card?.takeIf { it.hasAvatar }?.let { characters.avatarFile(it.id) }
            val avatar = avatarFile?.let { BitmapFactory.decodeFile(it.path) }
            _ui.update { it.copy(character = card, avatar = avatar, form = form) }
        }

        fun edit(change: (ContactForm) -> ContactForm) {
            _ui.update { it.copy(form = change(it.form)) }
        }

        /** Writes the form back: to the card, or to the assistant's name / profile / model. */
        fun save() {
            val state = _ui.value
            val form = state.form
            viewModelScope.launch(ioDispatcher) {
                val card = state.character
                if (card != null) {
                    val name = form.name.trim().ifBlank { card.name }
                    val edited = card.copy(name = name, description = form.description, backstory = form.backstory)
                    characters.update(edited.copy(model = form.model))
                } else {
                    settings.updateAssistantName(form.name)
                    settings.updateAssistantProfile(AssistantProfile(form.description, form.backstory))
                    settings.updateAssistantModel(form.model)
                }
                _ui.update { it.copy(notice = context.getString(R.string.contact_saved)) }
            }
        }

        fun changeAvatar(uri: Uri) {
            val card = _ui.value.character ?: return
            viewModelScope.launch(ioDispatcher) {
                val bytes = runCatching { ContentNames.open(context, uri).use { it.readBytes() } }.getOrNull()
                if (bytes != null) characters.updateAvatar(card.id, bytes)
            }
        }

        /** Reads the picked document (OCR when needed) and stores its text as a memory file. */
        fun addMemoryFile(uri: Uri) {
            if (_ui.value.reading) return
            _ui.update { it.copy(reading = true, notice = null) }
            viewModelScope.launch(ioDispatcher) {
                val result = reader.read(uri)
                result.fold(
                    onSuccess = { doc ->
                        val read = doc.extracted
                        val kept = runCatching { memory.add(owner, doc.name, doc.mime, read.text, read.ocr) }
                        _ui.update { it.copy(reading = false, notice = kept.exceptionOrNull()?.message) }
                    },
                    onFailure = { e -> _ui.update { it.copy(reading = false, notice = e.message ?: "无法读取文件") } },
                )
            }
        }

        fun setMemoryEnabled(
            id: String,
            enabled: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) { memory.setEnabled(owner, id, enabled) }
        }

        /** Opens the file [id] to read and correct, or an empty note when [id] is null. */
        fun editMemory(id: String?) {
            viewModelScope.launch(ioDispatcher) {
                val file = _ui.value.memory.firstOrNull { it.id == id }
                val text = file?.let { memory.text(owner, it.id) }.orEmpty()
                val draft = MemoryDraft(file?.id, file?.name.orEmpty(), text)
                _ui.update { it.copy(editingMemory = draft) }
            }
        }

        /** Closes the open memory: [text] is what to keep, null (or nothing but spaces) leaves it as it was. */
        fun finishMemory(text: String?) {
            val draft = _ui.value.editingMemory ?: return
            _ui.update { it.copy(editingMemory = null) }
            val kept = text?.trim().orEmpty()
            if (kept.isEmpty()) return
            viewModelScope.launch(ioDispatcher) {
                val saved =
                    runCatching {
                        if (draft.id == null) {
                            memory.add(owner, MemoryNotes.title(kept), MemoryNotes.MIME, kept)
                        } else {
                            memory.updateText(owner, draft.id, kept)
                        }
                    }
                saved.exceptionOrNull()?.let { e -> _ui.update { it.copy(notice = e.message) } }
            }
        }

        fun removeMemoryFile(id: String) {
            viewModelScope.launch(ioDispatcher) { memory.remove(owner, id) }
        }

        fun noticeShown() {
            _ui.update { it.copy(notice = null) }
        }
    }
