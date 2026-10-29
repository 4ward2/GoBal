package com.danielealbano.androidremotecontrolmcp.services.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/** The message a bubble answers, shown as a quote block above it (tap to jump back to it). */
@Serializable
data class QuotedMessage(
    val id: String,
    val speaker: String,
    val excerpt: String,
)

/** A picture or document sent in the chat; the file lives in the app's private storage. */
@Serializable
data class Attachment(
    val kind: String,
    val name: String,
    val path: String,
    val mime: String = "",
    /** Text extracted from a document (or OCR'd from a picture), so the model can read it. */
    val text: String = "",
) {
    val isImage: Boolean get() = kind == IMAGE

    companion object {
        const val IMAGE = "image"
        const val FILE = "file"
    }
}

@Serializable
data class ChatBubble(
    val role: String,
    val content: String,
    val id: String = "",
    val replyTo: QuotedMessage? = null,
    /** Who the message was forwarded from ("转发自 小夏"); null for an ordinary message. */
    val forwardedFrom: String? = null,
    val attachment: Attachment? = null,
    /** A recorded skill's card: the name of the skill it shows (the text is its step list, for the model). */
    val skill: String? = null,
    /**
     * The phone task this message stands for (see `AgentRuns`); the chat draws its progress card
     * instead of the text. Ids of the pre-agent helper process start with "wake:".
     */
    val task: String? = null,
    /** The memories the request for this answer carried, by id; lets the user find a wrong one. */
    val memories: List<String> = emptyList(),
    /** Memories that were in scope but did not fit the budget for this answer. */
    val memoriesLeft: Int = 0,
    /** What a reasoning model thought before this answer; shown folded, never sent back to a model. */
    val reasoning: String = "",
    /** Left out of what the model is sent (the user marked it "不计入上下文"); still shown and kept. */
    val excluded: Boolean = false,
)

/**
 * The open conversation of each contact, by character id (null: the plain assistant). Which
 * conversation is open, and the others a contact has, are [ConversationCatalog]'s business.
 */
@Singleton
class PhoneMcpChatHistory
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        /** Every conversation on disk; the old one-file-per-contact history is migrated on first use. */
        val files = ConversationFiles(context.filesDir)

        private fun ownerOf(characterId: String?): String = characterId ?: ConversationFiles.ASSISTANT

        fun load(characterId: String? = null): List<ChatBubble> {
            val owner = ownerOf(characterId)
            return files.load(owner, files.index(owner).active)
        }

        /** Everything is kept: what the model is sent is trimmed per request (ContextBudget.fit). */
        fun save(
            messages: List<ChatBubble>,
            characterId: String? = null,
        ) {
            val owner = ownerOf(characterId)
            files.save(owner, files.index(owner).active, messages)
        }

        fun loadAt(
            owner: String,
            id: String,
        ): List<ChatBubble> = files.load(owner, id)

        fun saveAt(
            owner: String,
            id: String,
            messages: List<ChatBubble>,
        ) = files.save(owner, id, messages)

        /** Forgets every conversation of a character (used when the character is deleted). */
        fun delete(characterId: String) {
            files.deleteOwner(characterId)
        }
    }
