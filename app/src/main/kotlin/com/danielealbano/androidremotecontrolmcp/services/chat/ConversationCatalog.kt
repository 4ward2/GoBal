package com.danielealbano.androidremotecontrolmcp.services.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A message found by searching every conversation. */
data class ConversationHit(
    val owner: String,
    val conversationId: String,
    val conversationTitle: String,
    val messageId: String,
    /** The words around the match, on one line. */
    val snippet: String,
)

/** Searching the stored conversations. Pure apart from reading [ConversationFiles]. */
object ConversationSearch {
    private const val BEFORE = 24
    private const val AFTER = 36

    /** Messages containing [query] (case ignored) across all of [owners], newest conversation first. */
    fun find(
        files: ConversationFiles,
        owners: List<String>,
        query: String,
        limit: Int,
    ): List<ConversationHit> {
        val wanted = query.trim()
        if (wanted.isEmpty()) return emptyList()
        return owners
            .asSequence()
            .flatMap { owner -> files.index(owner).conversations.map { owner to it } }
            .sortedByDescending { (_, meta) -> meta.updatedAt }
            .flatMap { (owner, meta) ->
                files.load(owner, meta.id).asSequence().mapNotNull { bubble ->
                    snippet(MarkdownText.plain(bubble.content), wanted)
                        ?.let { ConversationHit(owner, meta.id, meta.title, bubble.id, it) }
                }
            }.take(limit)
            .toList()
    }

    /** The stretch of [text] around the first [query] in it, or null when it is not there. */
    fun snippet(
        text: String,
        query: String,
    ): String? {
        val at = text.indexOf(query, ignoreCase = true)
        if (at < 0) return null
        val from = (at - BEFORE).coerceAtLeast(0)
        val to = (at + query.length + AFTER).coerceAtMost(text.length)
        val lead = if (from > 0) "…" else ""
        val tail = if (to < text.length) "…" else ""
        return lead + text.substring(from, to).replace('\n', ' ').trim() + tail
    }
}

/**
 * A contact's several conversations: listing, opening, starting, naming, pinning and deleting
 * them. Messages themselves go through [ConversationStore], which always works on the open one.
 */
@Singleton
class ConversationCatalog internal constructor(
    private val files: ConversationFiles,
    private val newId: () -> String,
    private val now: () -> Long,
) {
    @Inject
    constructor(history: PhoneMcpChatHistory) : this(
        history.files,
        { "c" + UUID.randomUUID().toString().take(ID_LENGTH) },
        System::currentTimeMillis,
    )

    private val _revision = MutableStateFlow(0)

    /** Changes whenever a different conversation becomes the open one: the chat then reloads. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private var jump: String? = null

    fun list(owner: String): List<ConversationMeta> = files.index(owner).sorted()

    fun activeId(owner: String): String = files.index(owner).active

    /** Makes [id] the open conversation; [jumpTo] is a message to scroll to once it is shown. */
    @Synchronized
    fun open(
        owner: String,
        id: String,
        jumpTo: String? = null,
    ) {
        val index = files.index(owner)
        if (index.find(id) == null) return
        files.writeIndex(owner, index.copy(active = id))
        jump = jumpTo
        _revision.update { it + 1 }
    }

    /** The message the last [open] asked to be shown, handed out once. */
    @Synchronized
    fun takeJump(): String? = jump.also { jump = null }

    /**
     * Opens a fresh conversation. One that has no question in it yet is reused rather than
     * leaving a trail of empty conversations behind every tap.
     */
    @Synchronized
    fun create(owner: String): ConversationMeta {
        val index = files.index(owner)
        val unused = index.sorted().firstOrNull { meta -> files.load(owner, meta.id).none { it.role == "user" } }
        val meta = unused ?: ConversationMeta(newId(), createdAt = now(), updatedAt = now())
        val listed = if (unused == null) index.conversations + meta else index.conversations
        files.writeIndex(owner, index.copy(active = meta.id, conversations = listed))
        _revision.update { it + 1 }
        return meta
    }

    /** Names the conversation; a blank [title] hands the name back to its first question. */
    @Synchronized
    fun rename(
        owner: String,
        id: String,
        title: String,
    ) {
        val index = files.index(owner)
        val meta = index.find(id) ?: return
        val clean = title.trim().take(MAX_TITLE)
        val renamed =
            if (clean.isEmpty()) {
                meta.copy(title = ConversationCodec.titleOf(files.load(owner, id)), named = false)
            } else {
                meta.copy(title = clean, named = true)
            }
        files.writeIndex(owner, index.replacing(renamed))
    }

    @Synchronized
    fun setPinned(
        owner: String,
        id: String,
        pinned: Boolean,
    ) {
        val index = files.index(owner)
        val meta = index.find(id) ?: return
        files.writeIndex(owner, index.replacing(meta.copy(pinned = pinned)))
    }

    /**
     * Deletes the conversation and its messages. When it was the open one, the next in the list
     * opens instead; deleting the last one leaves a fresh empty conversation.
     */
    @Synchronized
    fun delete(
        owner: String,
        id: String,
    ) {
        val index = files.index(owner)
        if (index.find(id) == null) return
        val rest = index.conversations.filterNot { it.id == id }
        val remaining = rest.ifEmpty { listOf(ConversationMeta(newId(), createdAt = now(), updatedAt = now())) }
        val wasOpen = index.active == id
        val active = if (wasOpen) ConversationIndex(conversations = remaining).sorted().first().id else index.active
        files.writeIndex(owner, ConversationIndex(active, remaining))
        files.deleteMessages(owner, id)
        if (wasOpen) _revision.update { it + 1 }
    }

    /** Messages containing [query] in every contact's conversations. */
    fun search(query: String): List<ConversationHit> {
        val owners = files.owners()
        return ConversationSearch.find(files, owners, query, SEARCH_LIMIT)
    }

    companion object {
        const val MAX_TITLE = 40
        private const val ID_LENGTH = 8
        private const val SEARCH_LIMIT = 60
    }
}
