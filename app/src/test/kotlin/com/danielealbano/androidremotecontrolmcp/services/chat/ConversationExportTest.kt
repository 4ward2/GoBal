package com.danielealbano.androidremotecontrolmcp.services.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ConversationExport")
class ConversationExportTest {
    @Test
    fun `a conversation reads as who said what, with quotes and attachments named`() {
        val picture = Attachment(Attachment.IMAGE, "化验单.jpg", "/a.jpg")
        val messages =
            listOf(
                ChatBubble("user", "这是什么", id = "q", attachment = picture),
                ChatBubble("assistant", "是一张**化验单**。", id = "a", reasoning = "先看图"),
                ChatBubble("user", "谢谢", id = "t", replyTo = QuotedMessage("a", "小夏", "是一张化验单")),
                ChatBubble("assistant", "  ", id = "blank"),
            )
        val text = ConversationExport.markdown("看化验单", "小夏", messages)
        assertTrue(text.startsWith("# 看化验单\n\n与「小夏」的对话"))
        assertTrue(text.contains("**我**\n\n[图片：化验单.jpg]\n\n这是什么"))
        assertTrue(text.contains("**小夏**\n\n是一张**化验单**。"))
        assertTrue(text.contains("> 回复 小夏：是一张化验单\n\n谢谢"))
        assertFalse(text.contains("先看图"))
        assertEquals(3, Regex("\\*\\*(我|小夏)\\*\\*\n").findAll(text).count())
    }

    @Test
    fun `the file name is safe everywhere and never empty`() {
        assertEquals("a b c.md", ConversationExport.fileName("a/b:c"))
        assertEquals("对话.md", ConversationExport.fileName("  "))
        assertEquals(40 + 3, ConversationExport.fileName("长".repeat(90)).length)
    }
}
