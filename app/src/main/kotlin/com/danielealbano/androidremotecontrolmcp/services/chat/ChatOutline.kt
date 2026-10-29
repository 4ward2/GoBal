package com.danielealbano.androidremotecontrolmcp.services.chat

/** One of the user's questions, as the jump list shows it. */
data class OutlineEntry(
    val id: String,
    val number: Int,
    val title: String,
)

/** The user's questions in a conversation, each cut to its first line, for jumping around. Pure. */
object ChatOutline {
    private const val TITLE_CHARS = 40

    fun entries(messages: List<ChatBubble>): List<OutlineEntry> =
        messages
            .filter { it.role == "user" && it.skill == null }
            .mapIndexed { index, bubble -> OutlineEntry(bubble.id, index + 1, titleOf(bubble)) }

    /** The first line with anything on it; a picture or file alone is named by its label. */
    fun titleOf(bubble: ChatBubble): String {
        val line =
            MarkdownText
                .plain(bubble.content)
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
        val title = line ?: OutgoingMessages.excerptOf(bubble)
        return if (title.length <= TITLE_CHARS) title else title.take(TITLE_CHARS).trimEnd() + "…"
    }
}
