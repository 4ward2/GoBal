package com.danielealbano.androidremotecontrolmcp.services.chat

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("PhoneMcpChatHistory")
class PhoneMcpChatHistoryTest {
    @TempDir
    lateinit var dir: File

    private fun history() = PhoneMcpChatHistory(mockk<Context> { every { filesDir } returns dir })

    @Test
    fun `a long conversation is stored whole`() {
        val messages = (1..500).map { ChatBubble(if (it % 2 == 1) "user" else "assistant", "第${it}条", id = "m$it") }
        history().save(messages)
        assertEquals(messages, history().load())
    }

    @Test
    fun `each character keeps its own conversation and the marks on its messages`() {
        val marked = listOf(ChatBubble("user", "别带上这句", id = "a", excluded = true))
        history().save(marked, "abc")
        history().save(listOf(ChatBubble("user", "助手这边", id = "b")))
        assertEquals(marked, history().load("abc"))
        assertEquals(listOf("助手这边"), history().load().map { it.content })
    }

    @Test
    fun `history written before there were several conversations still loads`() {
        File(dir, "native_chat_history.json").writeText("""[{"role":"user","content":"老消息","id":"x"}]""")
        File(dir, "chat_abc.json").writeText("""[{"role":"assistant","content":"喵","id":"g"}]""")
        assertEquals(listOf(ChatBubble("user", "老消息", id = "x")), history().load())
        assertEquals(listOf(ChatBubble("assistant", "喵", id = "g")), history().load("abc"))
    }

    @Test
    fun `load and save follow whichever conversation is open`() {
        val history = history()
        history.save(listOf(ChatBubble("user", "第一段", id = "a")), "abc")
        val files = history.files
        files.save("abc", "c2", listOf(ChatBubble("user", "第二段", id = "b")))
        files.writeIndex("abc", files.index("abc").copy(active = "c2"))
        assertEquals(listOf("第二段"), history.load("abc").map { it.content })
        history.save(listOf(ChatBubble("user", "第二段改", id = "b")), "abc")
        assertEquals(listOf("第一段"), files.load("abc", ConversationFiles.MAIN).map { it.content })
        assertEquals(listOf("第二段改"), files.load("abc", "c2").map { it.content })
    }

    @Test
    fun `deleting a character forgets all of its conversations`() {
        val history = history()
        history.save(listOf(ChatBubble("user", "在", id = "a")), "abc")
        history.delete("abc")
        assertTrue(history().load("abc").isEmpty())
        assertTrue(history.files.owners().isEmpty())
    }
}
