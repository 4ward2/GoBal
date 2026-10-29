package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Where a message can be forwarded to: another conversation of this app. */
data class ForwardTarget(
    /** Character id, or null for the plain assistant. */
    val characterId: String?,
    val name: String,
)

/**
 * Message-level edits shared by the chat screen: deleting a bubble, quoting one for a reply and
 * forwarding one into another conversation (the way messengers forward to another contact).
 */
@Singleton
class ConversationEditor
    @Inject
    constructor(
        private val history: PhoneMcpChatHistory,
        private val characters: CharacterRepository,
    ) {
        fun delete(
            messages: List<ChatBubble>,
            id: String,
        ): List<ChatBubble> = messages.filterNot { it.id == id }

        /** The quote block for replying to [bubble]; [speaker] is who wrote it. */
        fun quote(
            bubble: ChatBubble,
            speaker: String,
        ): QuotedMessage = QuotedMessage(bubble.id, speaker, OutgoingMessages.excerptOf(bubble))

        /** Every conversation except the active one, for the forward sheet. */
        suspend fun targets(
            activeCharacterId: String?,
            assistantName: String,
        ): List<ForwardTarget> {
            characters.load()
            val assistant = ForwardTarget(null, assistantName).takeIf { activeCharacterId != null }
            val others =
                characters.characters.value
                    .filter { it.id != activeCharacterId }
                    .map { ForwardTarget(it.id, it.name) }
            return listOfNotNull(assistant) + others
        }

        /**
         * Appends a copy of [bubble] to [target]'s conversation as a message the user sent there,
         * labelled with where it came from. The attachment file is shared, not copied.
         */
        fun forward(
            bubble: ChatBubble,
            from: String,
            target: ForwardTarget,
        ) {
            val copy =
                PhoneMcpChatLogic
                    .newBubble(role = "user", content = bubble.content)
                    .copy(forwardedFrom = from, attachment = bubble.attachment)
            history.save(history.load(target.characterId) + copy, target.characterId)
        }
    }
