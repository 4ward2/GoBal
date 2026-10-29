package com.danielealbano.androidremotecontrolmcp.services.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A conversation as a Markdown document a person can read, keep or send on. Pure. */
object ConversationExport {
    private const val USER = "我"
    private const val UNTITLED = "对话"
    private const val NAME_CHARS = 40
    private val UNSAFE = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /** [contact] is who the user talked to. The model's thinking is left out; hidden messages are kept as said. */
    fun markdown(
        title: String,
        contact: String,
        messages: List<ChatBubble>,
    ): String {
        val head = "# ${title.ifBlank { UNTITLED }}\n\n与「$contact」的对话，共 ${messages.size} 条。"
        val said = messages.mapNotNull { message -> entry(message, contact) }
        return (listOf(head) + said).joinToString("\n\n") + "\n"
    }

    /** A file name any phone or computer accepts. */
    fun fileName(title: String): String {
        val spaced = UNSAFE.replace(title, " ").trim()
        val clean = spaced.take(NAME_CHARS).trim()
        return clean.ifEmpty { UNTITLED } + ".md"
    }

    private fun entry(
        message: ChatBubble,
        contact: String,
    ): String? {
        val speaker = if (message.role == "user") USER else contact
        val quoted = message.replyTo?.let { "> 回复 ${it.speaker}：${it.excerpt}" }
        val from = message.forwardedFrom?.let { "（转发自 $it）" }
        val attached = message.attachment?.let { if (it.isImage) "[图片：${it.name}]" else "[文件：${it.name}]" }
        val body = listOfNotNull(quoted, from, attached, message.content.trim().takeIf { it.isNotEmpty() })
        return if (body.isEmpty()) null else "**$speaker**\n\n" + body.joinToString("\n\n")
    }
}

/** Writes an export where the app's file provider can hand it to another app. */
@Singleton
class ConversationExporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val history: PhoneMcpChatHistory,
    ) {
        /** The conversation [at] as a Markdown file. Only the newest export is kept. */
        fun export(
            at: ConversationRef,
            title: String,
            contact: String,
        ): File {
            val dir = File(context.filesDir, DIR)
            dir.listFiles()?.forEach { it.delete() }
            dir.mkdirs()
            val file = File(dir, ConversationExport.fileName(title))
            file.writeText(ConversationExport.markdown(title, contact, history.loadAt(at.owner, at.id)))
            return file
        }

        private companion object {
            /** Inside the one folder the file provider exposes. */
            const val DIR = "attachments/exports"
        }
    }
