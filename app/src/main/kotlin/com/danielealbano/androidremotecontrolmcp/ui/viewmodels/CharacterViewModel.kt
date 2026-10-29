package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.character.AvatarThumbnail
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterCard
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterFinder
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.character.DiscoveredCard
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpChatHistory
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A card found on the phone, ready for the import list. */
data class DiscoveredUi(
    val key: String,
    val name: String,
    val summary: String,
    val fileName: String,
    val thumbnail: Bitmap?,
    val alreadyImported: Boolean,
    val source: DiscoveredCard,
)

/** The "导入角色卡" sheet. */
data class ImportUiState(
    val open: Boolean = false,
    val hasPermission: Boolean = false,
    val scanning: Boolean = false,
    val found: List<DiscoveredUi> = emptyList(),
    val importing: String? = null,
)

/** Characters in the drawer: the list, who is active, their avatars, and one-tap import. */
@HiltViewModel
class CharacterViewModel
    @Inject
    constructor(
        private val repository: CharacterRepository,
        private val finder: CharacterFinder,
        private val history: PhoneMcpChatHistory,
        private val memory: MemoryRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val characters: StateFlow<List<CharacterCard>> = repository.characters
        val activeId: StateFlow<String?> = repository.activeId

        private val _avatars = MutableStateFlow<Map<String, Bitmap>>(emptyMap())

        /** Decoded avatar thumbnails by character id, loaded once so list rows never decode. */
        val avatars: StateFlow<Map<String, Bitmap>> = _avatars.asStateFlow()

        private val _import = MutableStateFlow(ImportUiState())
        val import: StateFlow<ImportUiState> = _import.asStateFlow()

        private val _notice = MutableStateFlow<String?>(null)

        /** One-off message for a toast ("已导入角色：小夏"). */
        val notice: StateFlow<String?> = _notice.asStateFlow()

        init {
            viewModelScope.launch(ioDispatcher) {
                repository.load()
                repository.characters.collect { cards -> _avatars.update { decodeMissing(it, cards) } }
            }
        }

        fun select(id: String?) {
            viewModelScope.launch(ioDispatcher) { repository.setActive(id) }
        }

        fun delete(id: String) {
            viewModelScope.launch(ioDispatcher) {
                repository.delete(id)
                history.delete(id)
                memory.removeOwner(id)
            }
        }

        fun noticeShown() {
            _notice.value = null
        }

        /** Opens the import sheet and, when allowed, looks for cards on the phone. */
        fun openImport() {
            _import.update { it.copy(open = true, hasPermission = finder.hasPermission()) }
            rescan()
        }

        fun closeImport() {
            _import.update { it.copy(open = false) }
        }

        /** Also called after the images permission was just granted. */
        fun rescan() {
            val allowed = finder.hasPermission()
            _import.update { it.copy(hasPermission = allowed, scanning = allowed) }
            if (!allowed) return
            viewModelScope.launch(ioDispatcher) {
                val found = finder.scan().map { it.toUi() }
                _import.update { it.copy(scanning = false, found = found) }
            }
        }

        fun importDiscovered(item: DiscoveredUi) {
            if (_import.value.importing != null) return
            _import.update { it.copy(importing = item.key) }
            viewModelScope.launch(ioDispatcher) {
                val result = repository.import(item.source.bytes)
                result.onSuccess { card ->
                    repository.setActive(card.id)
                    _notice.value = "已导入角色：${card.name}"
                    _import.update { state ->
                        state.copy(
                            importing = null,
                            open = false,
                            found =
                                state.found.map { f ->
                                    if (f.key ==
                                        item.key
                                    ) {
                                        f.copy(alreadyImported = true)
                                    } else {
                                        f
                                    }
                                },
                        )
                    }
                }
                result.onFailure { e ->
                    _notice.value = e.message ?: "导入失败"
                    _import.update { it.copy(importing = null) }
                }
            }
        }

        private suspend fun decodeMissing(
            current: Map<String, Bitmap>,
            cards: List<CharacterCard>,
        ): Map<String, Bitmap> =
            withContext(ioDispatcher) {
                val next = current.filterKeys { id -> cards.any { it.id == id } }.toMutableMap()
                cards.filter { it.hasAvatar && it.id !in next }.forEach { card ->
                    repository.avatarFile(card.id)?.let { file ->
                        BitmapFactory.decodeFile(file.path)?.let { next[card.id] = it }
                    }
                }
                next
            }

        private fun DiscoveredCard.toUi(): DiscoveredUi =
            DiscoveredUi(
                key = uri.toString(),
                name = card.name,
                summary = card.summary,
                fileName = fileName,
                thumbnail = decodeThumbnail(bytes),
                alreadyImported = alreadyImported,
                source = this,
            )

        private fun decodeThumbnail(png: ByteArray): Bitmap? =
            AvatarThumbnail.sampleSize(png, THUMB_SIDE)?.let { sample ->
                BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }

        private companion object {
            const val THUMB_SIDE = 128
        }
    }
