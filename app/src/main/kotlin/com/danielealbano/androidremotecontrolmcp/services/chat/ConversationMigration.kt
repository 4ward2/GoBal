package com.danielealbano.androidremotecontrolmcp.services.chat

import kotlinx.serialization.json.Json
import java.io.File

/** Where each piece of a contact's conversations is kept under the app's files directory. */
internal class ConversationPaths(
    private val root: File,
) {
    private val base = File(root, "conversations")

    fun dir(owner: String) = File(base, owner)

    fun index(owner: String) = File(dir(owner), INDEX)

    fun messages(
        owner: String,
        id: String,
    ) = File(dir(owner), "$id$JSON")

    /** A copy of a messages file that could not be read, kept for recovery. */
    fun broken(
        owner: String,
        id: String,
    ) = File(dir(owner), "$id$JSON.broken")

    /** The single history file [owner] had before conversations were introduced. */
    fun legacy(owner: String): File =
        File(root, if (owner == ConversationFiles.ASSISTANT) "native_chat_history.json" else "chat_$owner.json")

    /** Where [legacy] is kept, as a backup, once it has been brought over. */
    fun migrated(owner: String) = File(root, legacy(owner).name + ".migrated")

    fun owners(): List<String> =
        base
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .map { it.name }
            .sorted()

    /** The ids of the conversations whose messages are on disk for [owner]. */
    fun storedIds(owner: String): List<String> =
        dir(owner)
            .listFiles { file -> file.isFile && file.name.endsWith(JSON) && file.name != INDEX }
            .orEmpty()
            .map { it.name.removeSuffix(JSON) }
            .sorted()

    private companion object {
        const val INDEX = "index.json"
        const val JSON = ".json"
    }
}

/** Reading and writing the conversation files' JSON. */
internal object ConversationCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun messages(raw: String): List<ChatBubble>? {
        val decoded = runCatching { json.decodeFromString<List<ChatBubble>>(raw) }
        return decoded.getOrNull()
    }

    fun encode(messages: List<ChatBubble>): String = json.encodeToString(messages)

    fun encode(index: ConversationIndex): String = json.encodeToString(index)

    /** The index in [file], or null when it is missing, unreadable or lists nothing. */
    fun index(file: File): ConversationIndex? =
        file
            .takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString<ConversationIndex>(it.readText()) }.getOrNull() }
            ?.takeIf { it.conversations.isNotEmpty() }

    /** A conversation is called after the first thing the user asked in it. */
    fun titleOf(messages: List<ChatBubble>): String =
        messages
            .firstOrNull { it.role == "user" && it.skill == null }
            ?.let(ChatOutline::titleOf)
            .orEmpty()
}

/**
 * Brings a contact's history from before there were several conversations into the new layout,
 * and repairs a lost index. Neither ever deletes a message file.
 */
internal class ConversationMigration(
    private val paths: ConversationPaths,
    private val now: () -> Long,
) {
    /**
     * Turns the owner's old single history into the conversation [ConversationFiles.MAIN], in an
     * order that cannot lose it: the messages are copied and read back before the index is
     * written, and only then is the old file renamed (kept as `….migrated`, never deleted). If any
     * step fails the old file stays where it is, nothing is recorded, loading keeps reading it,
     * and the next start tries again. A file that cannot be parsed is left untouched and a copy is
     * set aside. With no old history, the owner starts with one empty conversation.
     */
    fun migrate(owner: String): ConversationIndex {
        val main = ConversationFiles.MAIN
        val legacy = paths.legacy(owner)
        val raw = legacy.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()
        if (raw.isBlank()) return ConversationIndex(main, listOf(ConversationMeta(main, createdAt = now())))
        val messages = ConversationCodec.messages(raw)
        val stamp = legacy.lastModified().takeIf { it > 0 } ?: now()
        val title = ConversationCodec.titleOf(messages.orEmpty())
        val index = ConversationIndex(main, listOf(ConversationMeta(main, title, createdAt = stamp, updatedAt = stamp)))
        if (messages == null) {
            runCatching { legacy.copyTo(paths.broken(owner, main), overwrite = true) }
        } else {
            copyThenRetire(owner, raw, messages, index)
        }
        return index
    }

    private fun copyThenRetire(
        owner: String,
        raw: String,
        messages: List<ChatBubble>,
        index: ConversationIndex,
    ) {
        val target = paths.messages(owner, ConversationFiles.MAIN)
        val copied =
            runCatching {
                AtomicFiles.write(target, raw)
                check(ConversationCodec.messages(target.readText()) == messages) { "the copy differs" }
                AtomicFiles.write(paths.index(owner), ConversationCodec.encode(index))
            }
        if (copied.isSuccess) {
            paths.legacy(owner).renameTo(paths.migrated(owner))
        } else {
            // Leave no half-made state behind: without an index the next read migrates afresh.
            target.delete()
        }
    }

    /**
     * The index remade from the message files on disk, for when `index.json` is gone or unreadable:
     * every conversation is listed again (names and pins are lost, messages are not). Null when
     * there are no message files, so the caller falls back to [migrate].
     */
    fun rebuild(owner: String): ConversationIndex? {
        val metas =
            paths.storedIds(owner).map { id ->
                val file = paths.messages(owner, id)
                val messages = runCatching { ConversationCodec.messages(file.readText()) }.getOrNull().orEmpty()
                val stamp = file.lastModified()
                ConversationMeta(id, ConversationCodec.titleOf(messages), createdAt = stamp, updatedAt = stamp)
            }
        if (metas.isEmpty()) return null
        val index = ConversationIndex(metas.maxBy { it.updatedAt }.id, metas)
        AtomicFiles.save(paths.index(owner), ConversationCodec.encode(index))
        return index
    }
}
