package com.danielealbano.androidremotecontrolmcp.services.chat

import kotlinx.serialization.Serializable
import java.io.File

/** One conversation with a contact, as the list of conversations shows it. */
@Serializable
data class ConversationMeta(
    val id: String,
    /** The first question's opening words, or the name the user gave it. "" until there is one. */
    val title: String = "",
    /** True once the user named it: the title then stops following the first question. */
    val named: Boolean = false,
    val pinned: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** A contact's conversations and which of them is open. */
@Serializable
data class ConversationIndex(
    val active: String = ConversationFiles.MAIN,
    val conversations: List<ConversationMeta> = listOf(ConversationMeta(ConversationFiles.MAIN)),
) {
    /** Pinned first, then the most recently used. */
    fun sorted(): List<ConversationMeta> =
        conversations.sortedWith(compareByDescending<ConversationMeta> { it.pinned }.thenByDescending { it.updatedAt })

    fun find(id: String): ConversationMeta? = conversations.firstOrNull { it.id == id }

    fun replacing(meta: ConversationMeta): ConversationIndex =
        copy(conversations = conversations.map { if (it.id == meta.id) meta else it })
}

/**
 * Conversations on disk: `conversations/<owner>/index.json` plus one `<id>.json` of messages per
 * conversation. An owner is a character id, or [ASSISTANT].
 *
 * Before there could be several conversations per contact, each contact had a single history
 * file; the first time an owner is read it becomes the conversation [MAIN] (see
 * [ConversationMigration]).
 */
class ConversationFiles(
    root: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val paths = ConversationPaths(root)
    private val migration = ConversationMigration(paths, now)

    /** Every owner that has conversations on disk. */
    fun owners(): List<String> = paths.owners()

    /**
     * The owner's conversations. Without a readable index the list is rebuilt from the message
     * files that are there, and failing that the old single history is brought over.
     */
    @Synchronized
    fun index(owner: String): ConversationIndex {
        val stored = ConversationCodec.index(paths.index(owner))
        val index = stored ?: migration.rebuild(owner) ?: migration.migrate(owner)
        val activeKnown = index.find(index.active) != null
        return if (activeKnown) index else index.copy(active = index.sorted().first().id)
    }

    @Synchronized
    fun writeIndex(
        owner: String,
        index: ConversationIndex,
    ) {
        AtomicFiles.save(paths.index(owner), ConversationCodec.encode(index))
    }

    @Synchronized
    fun load(
        owner: String,
        id: String,
    ): List<ChatBubble> {
        val file = paths.messages(owner, id)
        // A migration that could not finish leaves the old file as the main conversation's source.
        val source = if (!file.isFile && id == MAIN) paths.legacy(owner) else file
        val raw = source.takeIf { it.isFile }?.let { f -> runCatching { f.readText() }.getOrNull() }.orEmpty()
        if (raw.isBlank()) return emptyList()
        val decoded =
            ConversationCodec.messages(raw) ?: run {
                // Unreadable history is set aside, never overwritten by the next save.
                runCatching { source.copyTo(paths.broken(owner, id), overwrite = true) }
                emptyList()
            }
        return PhoneMcpChatLogic.ensureBubbleIds(decoded)
    }

    /** Stores the whole conversation (nothing is clipped) and keeps its index entry current. */
    @Synchronized
    fun save(
        owner: String,
        id: String,
        messages: List<ChatBubble>,
    ) {
        val index = index(owner)
        val written = runCatching { AtomicFiles.write(paths.messages(owner, id), ConversationCodec.encode(messages)) }
        if (written.isFailure) return
        val known = index.find(id) ?: ConversationMeta(id, createdAt = now())
        val title = if (known.named) known.title else ConversationCodec.titleOf(messages)
        val meta = known.copy(title = title, updatedAt = now())
        val listed = if (index.find(id) == null) index.copy(conversations = index.conversations + meta) else index
        writeIndex(owner, listed.replacing(meta))
    }

    @Synchronized
    fun deleteMessages(
        owner: String,
        id: String,
    ) {
        paths.messages(owner, id).delete()
    }

    /** Forgets everything about [owner] (its character was deleted), the pre-migration file included. */
    @Synchronized
    fun deleteOwner(owner: String) {
        val dir = paths.dir(owner)
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
        paths.legacy(owner).delete()
        paths.migrated(owner).delete()
    }

    companion object {
        const val ASSISTANT = "assistant"
        const val MAIN = "main"
    }
}
