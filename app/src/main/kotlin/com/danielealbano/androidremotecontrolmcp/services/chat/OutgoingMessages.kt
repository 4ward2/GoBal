package com.danielealbano.androidremotecontrolmcp.services.chat

import java.io.File

/** Pure shaping of stored bubbles into what a model receives; unit-tested. */
object OutgoingMessages {
    private const val MAX_ATTACHMENT_CHARS = 12_000
    private const val EXCERPT_CHARS = 120
    private const val ROLE_SYSTEM = "system"

    /**
     * The system prompt followed by the conversation, quotes and attachments spelled out.
     * Messages the user left out of the context are not sent.
     */
    fun toModelMessages(
        system: String,
        bubbles: List<ChatBubble>,
    ): List<ChatMessage> {
        val sent = bubbles.filterNot { it.excluded }
        return listOf(ChatMessage(ROLE_SYSTEM, system)) + sent.map { toModelMessage(it) }
    }

    fun toModelMessage(bubble: ChatBubble): ChatMessage {
        val parts = mutableListOf<String>()
        bubble.replyTo?.let { parts += "> 回复 ${it.speaker}：${it.excerpt}" }
        bubble.forwardedFrom?.let { parts += "（转发自 $it）" }
        val attachment = bubble.attachment
        val images = mutableListOf<File>()
        when {
            attachment == null -> {
                Unit
            }

            attachment.isImage -> {
                images += File(attachment.path)
                attachment.text.takeIf { it.isNotBlank() }?.let { parts += "[图片文字]\n${it.take(MAX_ATTACHMENT_CHARS)}" }
            }

            else -> {
                parts += "[文件：${attachment.name}]\n${attachment.text.take(MAX_ATTACHMENT_CHARS)}".trimEnd()
            }
        }
        if (bubble.content.isNotBlank()) parts += bubble.content
        return ChatMessage(bubble.role, parts.joinToString("\n"), images)
    }

    /** What a quote block shows for a message: its text, or a label for a picture / file. */
    fun excerptOf(bubble: ChatBubble): String {
        val attachment = bubble.attachment
        val label =
            when {
                attachment == null -> ""
                attachment.isImage -> "[图片]"
                else -> "[文件] ${attachment.name}"
            }
        val text = MarkdownText.plain(bubble.content).trim().replace('\n', ' ')
        return listOf(label, text).filter { it.isNotBlank() }.joinToString(" ").let(::shorten)
    }

    fun shorten(text: String): String {
        if (text.length <= EXCERPT_CHARS) return text
        return text.take(EXCERPT_CHARS).trimEnd() + "…"
    }
}
