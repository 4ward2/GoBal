package com.danielealbano.androidremotecontrolmcp.ui.chat

import com.danielealbano.androidremotecontrolmcp.services.chat.Attachment
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBubble
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationEdits
import com.danielealbano.androidremotecontrolmcp.services.chat.QuotedMessage

/** One row of the conversation as the chat screen draws it. */
sealed interface ChatItem {
    val key: String

    /** The stored message id (same as [key] for stored bubbles), used by long-press actions. */
    val messageId: String

    data class UserMessage(
        override val key: String,
        val text: String,
        val quote: QuotedMessage? = null,
        val forwardedFrom: String? = null,
        val attachment: Attachment? = null,
    ) : ChatItem {
        override val messageId: String get() = key
    }

    data class AssistantMessage(
        override val key: String,
        val text: String,
        val quote: QuotedMessage? = null,
        val forwardedFrom: String? = null,
        val attachment: Attachment? = null,
        /** True when the message right before it is the question it answered: it can be asked for again. */
        val regenerable: Boolean = false,
        /** What the model thought before answering; shown folded above the answer. */
        val reasoning: String = "",
        /** The memories the answer was given; null when it was given none. */
        val memory: MemoryNote? = null,
    ) : ChatItem {
        override val messageId: String get() = key
    }

    /** A recorded skill being reviewed with the agent: its steps as draggable cards. */
    data class SkillReview(
        override val key: String,
        val skillName: String,
        val text: String,
    ) : ChatItem {
        override val messageId: String get() = key
    }

    /** A request handed to the on-device phone agent, shown as a task card instead of raw status text. */
    data class PhoneTask(
        override val key: String,
        val request: String,
        val taskId: String,
    ) : ChatItem {
        override val messageId: String get() = key
    }
}

/**
 * Maps stored chat history to what the chat screen shows.
 *
 * A message that stands for a phone task (`ChatBubble.task`) becomes a [ChatItem.PhoneTask] card
 * titled with the user request that started it. History written before tasks had their own field
 * carried the id inside a fixed sentence; those messages are still recognised.
 */
object ChatPresentation {
    private val WAKE_ACK = Regex("^已交给机上 PhoneMCP agent 执行手机操作（wake_id=([^）]*)）")

    private const val MORNING_START = 5
    private const val NOON_START = 11
    private const val AFTERNOON_START = 13
    private const val EVENING_START = 18

    /** The task id inside an acknowledgement sentence of the old kind, or null for any other text. */
    fun wakeAckTaskId(content: String): String? = WAKE_ACK.find(content)?.groupValues?.get(1)

    fun toItems(messages: List<ChatBubble>): List<ChatItem> =
        messages.mapIndexed { index, bubble ->
            val key = bubble.id.ifBlank { "i$index-${bubble.role}-${bubble.content.hashCode()}" }
            val taskId = if (bubble.role == ROLE_USER) null else bubble.task ?: wakeAckTaskId(bubble.content)
            val skill = bubble.skill
            when {
                skill != null -> {
                    ChatItem.SkillReview(key, skill, bubble.content)
                }

                bubble.role == ROLE_USER -> {
                    ChatItem.UserMessage(key, bubble.content, bubble.replyTo, bubble.forwardedFrom, bubble.attachment)
                }

                taskId != null -> {
                    ChatItem.PhoneTask(key, precedingUserText(messages, index), taskId)
                }

                else -> {
                    val attachment = bubble.attachment
                    val again = ConversationEdits.canRegenerate(messages, index)
                    val quote = bubble.replyTo
                    val from = bubble.forwardedFrom
                    val shown = ChatItem.AssistantMessage(key, bubble.content, quote, from, attachment, again)
                    val note = bubble.memories.takeIf { it.isNotEmpty() }?.let { MemoryNote(it, bubble.memoriesLeft) }
                    shown.copy(reasoning = bubble.reasoning, memory = note)
                }
            }
        }

    /** Greeting for the empty conversation, by local hour of day (0–23). */
    fun greeting(hour: Int): String =
        when (hour) {
            in MORNING_START until NOON_START -> "早上好"
            in NOON_START until AFTERNOON_START -> "中午好"
            in AFTERNOON_START until EVENING_START -> "下午好"
            else -> "晚上好"
        }

    private fun precedingUserText(
        messages: List<ChatBubble>,
        index: Int,
    ): String =
        messages
            .subList(0, index)
            .lastOrNull { it.role == ROLE_USER }
            ?.content
            .orEmpty()

    private const val ROLE_USER = "user"
}
