package com.danielealbano.androidremotecontrolmcp.services.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ChatOutline")
class ChatOutlineTest {
    @Test
    fun `only the questions the user asked are listed, numbered in order`() {
        val messages =
            listOf(
                ChatBubble("assistant", "喵", id = "g"),
                ChatBubble("user", "第一个问题", id = "q1"),
                ChatBubble("assistant", "回答", id = "a1"),
                ChatBubble("user", "第二个问题", id = "q2"),
                ChatBubble("user", "步骤", id = "s", skill = "技能"),
            )
        assertEquals(
            listOf(OutlineEntry("q1", 1, "第一个问题"), OutlineEntry("q2", 2, "第二个问题")),
            ChatOutline.entries(messages),
        )
    }

    @Test
    fun `a question is cut to its first non-empty line without markdown markers`() {
        val bubble = ChatBubble("user", "\n\n**帮我**看看这段代码\n```\nval a = 1\n```", id = "q")
        assertEquals("帮我看看这段代码", ChatOutline.titleOf(bubble))
    }

    @Test
    fun `a long first line is shortened`() {
        val title = ChatOutline.titleOf(ChatBubble("user", "字".repeat(100), id = "q"))
        assertEquals(41, title.length)
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun `a picture or file alone is named by its label`() {
        val picture = Attachment(Attachment.IMAGE, "cat.jpg", "/tmp/cat.jpg")
        val file = Attachment(Attachment.FILE, "账单.pdf", "/tmp/a.pdf")
        assertEquals("[图片]", ChatOutline.titleOf(ChatBubble("user", "", id = "p", attachment = picture)))
        assertEquals("[文件] 账单.pdf", ChatOutline.titleOf(ChatBubble("user", "", id = "f", attachment = file)))
    }
}
