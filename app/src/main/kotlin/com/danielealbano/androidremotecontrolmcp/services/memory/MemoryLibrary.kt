package com.danielealbano.androidremotecontrolmcp.services.memory

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A memory to be written down. */
data class NewMemory(
    val text: String,
    /** "" to name it after its first words. */
    val title: String = "",
    val scope: String = MemoryEntry.GLOBAL,
    val source: MemorySource = MemorySource(),
    val mime: String = MemoryNotes.MIME,
    val ocr: Boolean = false,
)

/** What changes about an entry; a null leaves that part as it is. */
data class MemoryEdit(
    val text: String? = null,
    val scope: String? = null,
    val enabled: Boolean? = null,
)

/**
 * The app's memory: what the user had it remember, for every contact and every model alike. The
 * user can read, correct, switch off, merge and delete every entry; nothing is remembered that
 * [guard] refuses.
 */
open class MemoryLibrary(
    dir: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val guard: MemoryGuard = MemoryGuard(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val files = MemoryLibraryFiles(dir)
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<MemoryEntry>>(emptyList())

    /** Every entry, newest first; filled by [load]. */
    val entries: StateFlow<List<MemoryEntry>> = _entries.asStateFlow()

    /** Reads the library (taking in old per-contact files on the way). */
    suspend fun load(): List<MemoryEntry> = mutex.withLock { withContext(ioDispatcher) { publish() } }

    suspend fun text(id: String): String = withContext(ioDispatcher) { files.text(id) }

    /** Writes [memory] down, or says why not. */
    suspend fun add(memory: NewMemory): Result<MemoryEntry> =
        checked(memory.text) { text ->
            val now = clock()
            val title = memory.title.ifBlank { MemoryNotes.title(text) }
            val entry =
                MemoryEntry(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    scope = memory.scope,
                    chars = text.length,
                    source = memory.source,
                    mime = memory.mime,
                    ocr = memory.ocr,
                    createdAt = now,
                    updatedAt = now,
                )
            files.put(entry, text)
            entry
        }

    /** Changes the entry [id]; a new text goes through the same guard as a new memory. */
    suspend fun edit(
        id: String,
        change: MemoryEdit,
    ): Result<MemoryEntry> {
        val text = change.text
        val apply: (String?) -> MemoryEntry = { kept ->
            val old = files.entries().first { it.id == id }
            val next =
                old.copy(
                    scope = change.scope ?: old.scope,
                    enabled = change.enabled ?: old.enabled,
                    chars = kept?.length ?: old.chars,
                    updatedAt = if (kept == null) old.updatedAt else clock(),
                )
            val listing = files.entries().map { if (it.id == id) next else it }
            if (kept == null) files.save(listing) else files.put(next, kept)
            next
        }
        return if (text == null) written { apply(null) } else checked(text) { apply(it) }
    }

    suspend fun remove(ids: Set<String>) {
        written { files.delete(ids) }
    }

    /** Puts the entries [ids] together as one (in the order listed) and removes the originals. */
    suspend fun merge(ids: List<String>): Result<MemoryEntry> {
        val parts = _entries.value.filter { it.id in ids }.sortedBy { ids.indexOf(it.id) }
        val first = parts.firstOrNull() ?: return Result.failure(IllegalArgumentException(NOTHING_TO_MERGE))
        val text = parts.map { files.text(it.id).trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
        // Merged memories reach everyone only if every part did.
        val scope = if (parts.all { it.scope == first.scope }) first.scope else MemoryEntry.GLOBAL
        val made = add(NewMemory(text, first.title, scope, MemorySource(MemorySource.MANUAL)))
        if (made.isSuccess) remove(ids.toSet())
        return made
    }

    /**
     * What the contact [owner] is given for a request about [query], within what a model with a
     * [window]-token context may carry. Entries that go in are marked as just used.
     */
    suspend fun select(
        owner: String,
        query: String,
        window: Int,
        mark: Boolean = true,
    ): MemorySelection =
        mutex.withLock {
            withContext(ioDispatcher) {
                val now = clock()
                val all = files.entries()
                val candidates = all.filter { it.enabled && it.reaches(owner) }.map { it to files.text(it.id) }
                val chosen = MemorySelector.select(candidates, query, MemorySelector.budget(window), now)
                val usedIds = chosen.used.map { it.entry.id }.toSet()
                // Best effort: a request is not held up because the note of its use could not be written.
                if (mark && usedIds.isNotEmpty()) {
                    runCatching { files.markUsed(usedIds, now) }
                    publish()
                }
                chosen
            }
        }

    private suspend fun checked(
        text: String,
        write: (String) -> MemoryEntry,
    ): Result<MemoryEntry> =
        when (val verdict = guard.check(text.trim())) {
            is MemoryVerdict.Refuse -> Result.failure(IllegalArgumentException(verdict.reason))
            is MemoryVerdict.Keep -> written { write(verdict.text) }
        }

    private suspend fun <T> written(write: () -> T): Result<T> =
        mutex.withLock {
            withContext(ioDispatcher) {
                val done = runCatching { write() }
                publish()
                done
            }
        }

    private fun publish(): List<MemoryEntry> {
        val listed = files.entries().sortedByDescending { it.updatedAt }
        _entries.value = listed
        return listed
    }

    companion object {
        const val NOTHING_TO_MERGE = "没有选中要合并的记忆"
    }
}
