package com.danielealbano.androidremotecontrolmcp.services.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

@DisplayName("OutgoingMessages")
class OutgoingMessagesTest {
    @Test
    fun `quotes, forwards and attachments are spelled out for the model`() {
        val quoted = ChatBubble("user", "回你", "2", replyTo = QuotedMessage("1", "小夏", "喵喵"))
        val forwarded = ChatBubble("user", "转发的", "3", forwardedFrom = "老张")
        val file = Attachment(Attachment.FILE, "a.pdf", "/p/a.pdf", text = "正文")
        val doc = ChatBubble("user", "看看", "4", attachment = file)
        val image = Attachment(Attachment.IMAGE, "c.jpg", "/p/c.jpg", text = "图中字")
        val pic = ChatBubble("user", "", "5", attachment = image)

        val out = OutgoingMessages.toModelMessages("sys", listOf(quoted, forwarded, doc, pic))
        assertEquals(ChatMessage("system", "sys"), out[0])
        assertEquals("> 回复 小夏：喵喵\n回你", out[1].text)
        assertEquals("（转发自 老张）\n转发的", out[2].text)
        assertEquals("[文件：a.pdf]\n正文\n看看", out[3].text)
        assertEquals(listOf(File("/p/c.jpg")), out[4].images)
        assertEquals("[图片文字]\n图中字", out[4].text)
    }

    @Test
    fun `excerpts label pictures and files and stay short`() {
        val pic = ChatBubble("user", "看", "1", attachment = Attachment(Attachment.IMAGE, "c.jpg", "/p"))
        assertEquals("[图片] 看", OutgoingMessages.excerptOf(pic))
        val doc = ChatBubble("user", "", "2", attachment = Attachment(Attachment.FILE, "a.pdf", "/p"))
        assertEquals("[文件] a.pdf", OutgoingMessages.excerptOf(doc))
        val long = ChatBubble("assistant", "字".repeat(200) + "\n第二行", "3")
        val excerpt = OutgoingMessages.excerptOf(long)
        assertEquals(121, excerpt.length)
        assertEquals("…", excerpt.takeLast(1))
    }
}
