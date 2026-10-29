package com.danielealbano.androidremotecontrolmcp.services.memory

import com.danielealbano.androidremotecontrolmcp.services.chat.AtomicFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Where a memory came from, so the user can judge it and find its origin. */
@Serializable
data class MemorySource(
    /** [MANUAL], [FILE] or [CHAT]. */
    val kind: String = MANUAL,
    /** For [CHAT]: whose conversation, which one and which message. */
    val owner: String = "",
    val conversation: String = "",
    val messageId: String = "",
) {
    companion object {
        const val MANUAL = "manual"
        const val FILE = "file"
        const val CHAT = "chat"
    }
}

/**
 * One thing the app remembers. It lives in the app, not in any model: whichever provider or
 * on-device model answers, the app puts the memories that fit into the request. [scope] says who
 * gets it: every contact ([GLOBAL]) or one of them (a character id, or the assistant).
 */
@Serializable
data class MemoryEntry(
    val id: String,
    /** A file's name, or the first words of a note. */
    val title: String,
    val scope: String = GLOBAL,
    val enabled: Boolean = true,
    val chars: Int = 0,
    val source: MemorySource = MemorySource(),
    val mime: String = "",
    /** True when the text came out of OCR rather than a text layer. */
    val ocr: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** When it last went into a request; 0 for never. */
    val lastUsedAt: Long = 0L,
) {
    /** True when the contact [owner] is given this memory. */
    fun reaches(owner: String): Boolean = scope == GLOBAL || scope == owner

    companion object {
        const val GLOBAL = ""
    }
}

@Serializable
data class MemoryIndex(
    val format: Int = FORMAT,
    val entries: List<MemoryEntry> = emptyList(),
) {
    companion object {
        const val FORMAT = 1
    }
}

/**
 * The library on disk: `<dir>/library/index.json` lists the entries, each text is in
 * `<dir>/library/<id>.txt`.
 *
 * Before the library there was one folder of memory files per contact (`<dir>/<owner>/`). Those
 * are taken in the first time they are seen: copy each text, read it back, write the index, and
 * only then rename the old index to `index.json.migrated` (the old texts stay where they are).
 * If any step fails nothing is renamed, the old files keep being read as they are, and the next
 * start tries again.
 */
class MemoryLibraryFiles(
    private val dir: File,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val home = File(dir, LIBRARY)
    private val indexFile = File(home, INDEX)

    private val old = OldMemoryFolders(dir, LIBRARY)

    /** Texts still in a contact's old folder (a migration that could not finish), by entry id. */
    private val stranded = mutableMapOf<String, File>()

    /** Every entry: the library's own, plus any old per-contact files that could not be taken in yet. */
    @Synchronized
    fun entries(): List<MemoryEntry> {
        var known = read()
        stranded.clear()
        val leftBehind = mutableListOf<MemoryEntry>()
        old.owners().forEach { owner ->
            val taken = takeIn(owner, known)
            if (taken == null) leftBehind += strandedOf(owner, known) else known = taken
        }
        return known + leftBehind
    }

    @Synchronized
    fun text(id: String): String {
        val file = File(home, "$id$TXT").takeIf { it.isFile } ?: stranded[id]
        return file?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()
    }

    /** Stores [text] for [entry] and lists the entry; an entry with the same id is replaced. */
    @Synchronized
    fun put(
        entry: MemoryEntry,
        text: String,
    ) {
        AtomicFiles.write(File(home, "${entry.id}$TXT"), text)
        save(read().filterNot { it.id == entry.id } + entry)
    }

    /**
     * Replaces the listing of entries that are already stored; their texts are not touched.
     * Entries still stranded in an old folder are never listed here: they are not in the library
     * until their text is.
     */
    @Synchronized
    fun save(entries: List<MemoryEntry>) {
        val own = entries.filterNot { it.id in stranded }
        AtomicFiles.write(indexFile, json.encodeToString(MemoryIndex(entries = own)))
    }

    /** Notes that the entries [ids] went into a request at [now]. */
    @Synchronized
    fun markUsed(
        ids: Set<String>,
        now: Long,
    ) = save(read().map { if (it.id in ids) it.copy(lastUsedAt = now) else it })

    @Synchronized
    fun delete(ids: Set<String>) {
        save(read().filterNot { it.id in ids })
        ids.forEach { File(home, "$it$TXT").delete() }
    }

    private fun read(): List<MemoryEntry> {
        val raw = indexFile.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        val index = raw?.let { runCatching { json.decodeFromString<MemoryIndex>(it) }.getOrNull() }
        return index?.entries.orEmpty()
    }

    /** Takes the old folder of [owner] into the library; the new listing, or null when it could not be done. */
    private fun takeIn(
        owner: String,
        known: List<MemoryEntry>,
    ): List<MemoryEntry>? {
        val fresh = old.files(owner).filter { file -> known.none { it.id == file.id } }
        val moved =
            runCatching {
                fresh.forEach { file ->
                    val text = old.textFile(owner, file.id).readText()
                    val copy = File(home, "${file.id}$TXT")
                    AtomicFiles.write(copy, text)
                    check(copy.readText() == text) { "copy differs" }
                }
                val merged = known + fresh.map { old.entryOf(owner, it) }
                save(merged)
                check(read().size == merged.size) { "index differs" }
                merged
            }.getOrNull()
        // Once the library holds them the old index is retired; if that rename fails it is retried
        // next time, and the entries are simply found to be known already.
        if (moved != null) old.retire(owner)
        return moved
    }

    /** The old files of [owner] as entries read straight from the old folder. */
    private fun strandedOf(
        owner: String,
        known: List<MemoryEntry>,
    ): List<MemoryEntry> {
        val missing = old.files(owner).filter { file -> known.none { it.id == file.id } }
        missing.forEach { stranded[it.id] = old.textFile(owner, it.id) }
        return missing.map { old.entryOf(owner, it) }
    }

    private companion object {
        const val LIBRARY = "library"
        const val INDEX = "index.json"
        const val TXT = ".txt"
    }
}

/** The per-contact folders of memory files from before there was a library (`<dir>/<owner>/`). */
internal class OldMemoryFolders(
    private val dir: File,
    private val libraryFolder: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The contacts whose old folder still has its index. */
    fun owners(): List<String> {
        val folders = dir.listFiles().orEmpty().filter { it.isDirectory && it.name != libraryFolder }
        return folders.filter { File(it, INDEX).isFile }.map { it.name }.sorted()
    }

    fun files(owner: String): List<MemoryFile> {
        val raw = runCatching { File(File(dir, owner), INDEX).readText() }.getOrNull()
        return raw?.let { runCatching { json.decodeFromString<List<MemoryFile>>(it) }.getOrNull() }.orEmpty()
    }

    fun textFile(
        owner: String,
        id: String,
    ) = File(File(dir, owner), "$id$TXT")

    /** Renames the old index so the folder is not taken in again; the texts stay as a backup. */
    fun retire(owner: String): Boolean {
        val folder = File(dir, owner)
        return File(folder, INDEX).renameTo(File(folder, INDEX + MIGRATED))
    }

    fun entryOf(
        owner: String,
        old: MemoryFile,
    ) = MemoryEntry(
        id = old.id,
        title = old.name,
        scope = owner,
        enabled = old.enabled,
        chars = old.chars,
        source = MemorySource(MemorySource.FILE),
        mime = old.mime,
        ocr = old.ocr,
        createdAt = old.addedAt,
        updatedAt = old.addedAt,
    )

    private companion object {
        const val INDEX = "index.json"
        const val TXT = ".txt"
        const val MIGRATED = ".migrated"
    }
}
