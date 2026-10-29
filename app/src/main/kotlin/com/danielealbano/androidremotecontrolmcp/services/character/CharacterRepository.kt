package com.danielealbano.androidremotecontrolmcp.services.character

import com.danielealbano.androidremotecontrolmcp.services.chat.AtomicFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** Imported characters and which one the chat is talking to (null = the plain assistant). */
interface CharacterRepository {
    /** A stack: the most recently selected character on top, never-selected ones by import time. */
    val characters: StateFlow<List<CharacterCard>>
    val activeId: StateFlow<String?>

    suspend fun load()

    /**
     * Imports a card from a file's bytes (PNG or JSON). An already imported card is returned as is.
     *
     * @return the stored card, or a failure whose message says why the file was not a card.
     */
    suspend fun import(bytes: ByteArray): Result<CharacterCard>

    suspend fun delete(id: String)

    suspend fun setActive(id: String?)

    /** Rewrites a card the user edited on its contact page (matched by id; unknown ids are ignored). */
    suspend fun update(card: CharacterCard)

    /** Replaces the character's portrait with a picture the user picked. */
    suspend fun updateAvatar(
        id: String,
        png: ByteArray,
    )

    /** Records the character's self-reported status ("生闷气中"); blank clears it. */
    suspend fun updateStatus(
        id: String,
        status: String,
    ) {
        characters.value.firstOrNull { it.id == id }?.let { update(it.copy(status = status.trim())) }
    }

    /** Whether the card in [bytes] is already imported; null when the bytes are not a card. */
    fun isImported(bytes: ByteArray): Boolean? =
        CharacterCardParser.parse(bytes)?.let { parsed -> characters.value.any { it.id == parsed.card.id } }

    /** The stored avatar image for [id], or null when the card had none. */
    fun avatarFile(id: String): File?
}

/** Shrinks an avatar PNG for storage; returns null to store no avatar. */
fun interface AvatarThumbnailer {
    fun shrink(png: ByteArray): ByteArray?
}

/**
 * One directory per install: `<id>.json` for the card and `<id>.png` for its (shrunken) avatar,
 * plus `active` holding the active id. Small enough to keep the whole list in memory.
 */
open class FileCharacterRepository(
    private val dir: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val thumbnailer: AvatarThumbnailer,
    private val clock: () -> Long = System::currentTimeMillis,
) : CharacterRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var loaded = false

    private val _characters = MutableStateFlow<List<CharacterCard>>(emptyList())
    override val characters: StateFlow<List<CharacterCard>> = _characters.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    override val activeId: StateFlow<String?> = _activeId.asStateFlow()

    override suspend fun load() {
        mutex.withLock { ensureLoaded() }
    }

    override suspend fun import(bytes: ByteArray): Result<CharacterCard> =
        mutex.withLock {
            ensureLoaded()
            val parsed = CharacterCardParser.parse(bytes)
            val existing = parsed?.let { p -> _characters.value.firstOrNull { it.id == p.card.id } }
            when {
                parsed == null -> Result.failure(IllegalArgumentException(NOT_A_CARD))
                existing != null -> Result.success(existing)
                else -> withContext(ioDispatcher) { store(parsed) }
            }
        }

    override suspend fun delete(id: String) {
        mutex.withLock {
            ensureLoaded()
            withContext(ioDispatcher) {
                File(dir, "$id.json").delete()
                File(dir, "$id.png").delete()
            }
            _characters.value = _characters.value.filterNot { it.id == id }
            if (_activeId.value == id) setActiveLocked(null)
        }
    }

    override suspend fun setActive(id: String?) {
        mutex.withLock {
            ensureLoaded()
            setActiveLocked(id?.takeIf { candidate -> _characters.value.any { it.id == candidate } })
        }
    }

    override suspend fun update(card: CharacterCard) {
        mutex.withLock {
            ensureLoaded()
            if (_characters.value.none { it.id == card.id }) return
            withContext(ioDispatcher) {
                AtomicFiles.save(File(dir, "${card.id}.json"), json.encodeToString(card))
            }
            _characters.value = _characters.value.map { if (it.id == card.id) card else it }.stacked()
        }
    }

    override suspend fun updateAvatar(
        id: String,
        png: ByteArray,
    ) {
        mutex.withLock {
            ensureLoaded()
            val card = _characters.value.firstOrNull { it.id == id } ?: return
            val shrunk = withContext(ioDispatcher) { thumbnailer.shrink(png) } ?: return
            val updated = card.copy(hasAvatar = true)
            withContext(ioDispatcher) {
                File(dir, "$id.png").writeBytes(shrunk)
                AtomicFiles.save(File(dir, "$id.json"), json.encodeToString(updated))
            }
            _characters.value = _characters.value.map { if (it.id == id) updated else it }
        }
    }

    override fun avatarFile(id: String): File? = File(dir, "$id.png").takeIf { it.isFile }

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(ioDispatcher) {
            dir.mkdirs()
            val cards =
                dir
                    .listFiles { f -> f.isFile && f.name.endsWith(".json") }
                    .orEmpty()
                    .mapNotNull { f -> runCatching { json.decodeFromString<CharacterCard>(f.readText()) }.getOrNull() }
                    .stacked()
            _characters.value = cards
            val active = runCatching { File(dir, ACTIVE).readText().trim() }.getOrNull()
            _activeId.value = active?.takeIf { id -> cards.any { it.id == id } }
        }
        loaded = true
    }

    private fun store(parsed: ParsedCard): Result<CharacterCard> {
        val avatar = parsed.avatarPng?.let { thumbnailer.shrink(it) }
        val card = parsed.card.copy(hasAvatar = avatar != null, importedAt = clock())
        return runCatching {
            if (avatar != null) File(dir, "${card.id}.png").writeBytes(avatar)
            File(dir, "${card.id}.json").writeText(json.encodeToString(card))
            _characters.value = (listOf(card) + _characters.value).stacked()
            card
        }.recoverCatching { e -> throw IOException("保存角色失败：${e.message}", e) }
    }

    private suspend fun setActiveLocked(id: String?) {
        _activeId.value = id
        val picked = id?.let { chosen -> _characters.value.firstOrNull { it.id == chosen } }?.copy(selectedAt = clock())
        withContext(ioDispatcher) {
            runCatching {
                if (id == null) File(dir, ACTIVE).delete() else File(dir, ACTIVE).writeText(id)
                if (picked != null) File(dir, picked.id + ".json").writeText(json.encodeToString(picked))
            }
        }
        if (picked != null) {
            _characters.value = _characters.value.map { if (it.id == picked.id) picked else it }.stacked()
        }
    }

    private companion object {
        const val ACTIVE = "active"
        const val NOT_A_CARD = "这不是角色卡（需要带角色信息的 PNG 或 JSON 文件）"
    }
}

/** Stack order: the character picked most recently on top; never-picked ones by import time. */
private fun List<CharacterCard>.stacked(): List<CharacterCard> =
    sortedWith(compareByDescending<CharacterCard> { it.selectedAt }.thenByDescending { it.importedAt })
