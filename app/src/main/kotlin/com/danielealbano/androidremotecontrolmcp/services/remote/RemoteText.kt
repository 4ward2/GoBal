package com.danielealbano.androidremotecontrolmcp.services.remote

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBubble

/** Which agent on which computer a contact stands for. Carried in the contact's [ModelChoice]. Pure. */
data class RemoteContact(
    val computerId: String,
    val agentId: String,
) {
    fun choice(): ModelChoice = ModelChoice(PREFIX + computerId, agentId)

    companion object {
        private const val PREFIX = "remote:"

        fun of(choice: ModelChoice): RemoteContact? {
            val remote = choice.providerId.startsWith(PREFIX) && choice.model.isNotEmpty()
            return if (remote) RemoteContact(choice.providerId.removePrefix(PREFIX), choice.model) else null
        }
    }
}

/** Turns what an agent did into the text of chat messages. Pure. */
object RemoteText {
    /** A tool call as one quoted line; "" when there is nothing to say about it yet. */
    fun tool(item: RemoteItem.Tool): String = if (item.summary.isEmpty()) "" else "> ${item.name}：${item.summary}"

    /**
     * The conversation as chat messages: what the user said, and between two things the user said
     * everything the agent answered as one message, its tool calls as quoted lines in place.
     */
    fun bubbles(items: List<RemoteItem>): List<ChatBubble> {
        val out = mutableListOf<ChatBubble>()
        val answer = AnswerText()
        items.forEach { item ->
            if (item is RemoteItem.User) {
                answer.bubble(out.size)?.let { out += it }
                out += ChatBubble("user", item.text, id = "ru-" + item.messageId.ifEmpty { out.size.toString() })
            } else {
                answer.add(item)
            }
        }
        answer.bubble(out.size)?.let { out += it }
        return out
    }
}

/** Puts an agent's answer together from its pieces, as they arrive or all at once. */
class AnswerText {
    private val text = StringBuilder()
    private val thought = StringBuilder()
    private var lastMessage = ""
    private val toolsShown = mutableSetOf<String>()

    /** Adds [item] and returns what it adds to the text shown ("" for nothing new). */
    fun add(item: RemoteItem): String {
        val piece =
            when (item) {
                is RemoteItem.Assistant -> separator(item.messageId) + item.text
                is RemoteItem.Tool -> toolLine(item)
                is RemoteItem.Reasoning -> "".also { thought.append(item.text) }
                is RemoteItem.User -> ""
            }
        text.append(piece)
        return piece
    }

    /** A new message of the same answer starts a new paragraph. */
    private fun separator(messageId: String): String {
        val another = text.isNotEmpty() && messageId != lastMessage
        lastMessage = messageId
        return if (another) PARAGRAPH else ""
    }

    /** A tool call is reported several times as it runs: it is shown once, when it first says what it does. */
    private fun toolLine(item: RemoteItem.Tool): String {
        val line = RemoteText.tool(item)
        val fresh = line.isNotEmpty() && toolsShown.add(item.callId)
        lastMessage = ""
        val lead = if (text.isEmpty()) "" else PARAGRAPH
        return if (fresh) lead + line else ""
    }

    fun text(): String = text.toString()

    /** What the agent thought on the way, when its program says. */
    fun thought(): String = thought.toString().trim()

    /** The answer so far as one chat message (null when there is none), and a clean slate for the next. */
    fun bubble(index: Int): ChatBubble? {
        val said = text().takeIf { it.isNotBlank() }
        val made = said?.let { ChatBubble("assistant", it, id = "ra-$index", reasoning = thought()) }
        text.clear()
        thought.clear()
        lastMessage = ""
        toolsShown.clear()
        return made
    }

    private companion object {
        const val PARAGRAPH = "\n\n"
    }
}
