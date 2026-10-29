package com.danielealbano.androidremotecontrolmcp.services.chat

/** A conversation cut back to just before [target]; [dropped] messages after it would be discarded. */
data class Rewind(
    val kept: List<ChatBubble>,
    val target: ChatBubble,
    val dropped: Int,
)

/** Going back in a conversation: editing a question, or asking for an answer again. Pure. */
object ConversationEdits {
    private const val ROLE_USER = "user"

    /** The cut for editing the user's message [id] and sending it again; null when it cannot be edited. */
    fun forEdit(
        messages: List<ChatBubble>,
        id: String,
    ): Rewind? {
        val index = messages.indexOfFirst { it.id == id }
        val target = messages.getOrNull(index)?.takeIf { it.role == ROLE_USER && it.skill == null }
        return target?.let { Rewind(messages.take(index), it, messages.size - index - 1) }
    }

    /** The cut for answering again in place of the reply [id]; null unless it answers a user message. */
    fun forRegenerate(
        messages: List<ChatBubble>,
        id: String,
    ): Rewind? {
        val index = messages.indexOfFirst { it.id == id }
        return if (canRegenerate(messages, index)) {
            Rewind(messages.take(index), messages[index], messages.size - index - 1)
        } else {
            null
        }
    }

    /** A reply can be asked for again when the message right before it is the question it answered. */
    fun canRegenerate(
        messages: List<ChatBubble>,
        index: Int,
    ): Boolean {
        val reply = messages.getOrNull(index)
        val question = messages.getOrNull(index - 1)
        val isReply = reply != null && reply.role != ROLE_USER && reply.skill == null
        return isReply && question?.role == ROLE_USER && !question.excluded
    }

    private fun isQuestionAndAnswer(
        first: ChatBubble,
        second: ChatBubble,
    ): Boolean = first.role == ROLE_USER && second.role != ROLE_USER

    /**
     * [messages] with those in [ids] left out of (or put back into) what the model is sent.
     * Leaving out a question also leaves out the answer right after it, so no orphan answer is
     * sent; putting a message back touches that message alone.
     */
    fun setExcluded(
        messages: List<ChatBubble>,
        ids: Set<String>,
        excluded: Boolean,
    ): List<ChatBubble> {
        val paired =
            if (excluded) {
                messages
                    .zipWithNext()
                    .filter { (question, answer) -> question.id in ids && isQuestionAndAnswer(question, answer) }
                    .map { it.second.id }
            } else {
                emptyList()
            }
        val all = ids + paired
        return messages.map { if (it.id in all && it.excluded != excluded) it.copy(excluded = excluded) else it }
    }
}
