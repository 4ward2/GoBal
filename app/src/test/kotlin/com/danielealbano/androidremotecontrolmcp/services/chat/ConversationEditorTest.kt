package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.services.character.CharacterCard
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ConversationEditor")
class ConversationEditorTest {
    private val history = mockk<PhoneMcpChatHistory>(relaxed = true)
    private val characters =
        mockk<CharacterRepository> {
            coEvery { load() } returns Unit
            every { this@mockk.characters } returns
                MutableStateFlow(listOf(CharacterCard("a", "小夏"), CharacterCard("b", "老张")))
        }
    private val editor = ConversationEditor(history, characters)

    @Test
    fun `targets are every other conversation, the assistant included when a character is active`() =
        runTest {
            assertEquals(
                listOf(ForwardTarget(null, "小助手"), ForwardTarget("b", "老张")),
                editor.targets(activeCharacterId = "a", assistantName = "小助手"),
            )
            assertEquals(
                listOf(ForwardTarget("a", "小夏"), ForwardTarget("b", "老张")),
                editor.targets(activeCharacterId = null, assistantName = "小助手"),
            )
        }

    @Test
    fun `forwarding appends a labelled copy to the target's history`() {
        every { history.load("b") } returns listOf(ChatBubble("assistant", "开场", "x"))
        val original = ChatBubble("assistant", "喵", "1", attachment = Attachment(Attachment.IMAGE, "c.jpg", "/p"))
        editor.forward(original, from = "小夏", target = ForwardTarget("b", "老张"))
        val saved = slot<List<ChatBubble>>()
        verify { history.save(capture(saved), "b") }
        val copy = saved.captured.last()
        assertEquals("user", copy.role)
        assertEquals("喵", copy.content)
        assertEquals("小夏", copy.forwardedFrom)
        assertEquals(original.attachment, copy.attachment)
        assertEquals(2, saved.captured.size)
    }

    @Test
    fun `quote and delete`() {
        val messages = listOf(ChatBubble("user", "a", "1"), ChatBubble("assistant", "b", "2"))
        assertEquals(QuotedMessage("2", "小夏", "b"), editor.quote(messages[1], "小夏"))
        assertEquals(listOf(messages[0]), editor.delete(messages, "2"))
    }
}
