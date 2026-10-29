package com.danielealbano.androidremotecontrolmcp.ui.chat

import com.danielealbano.androidremotecontrolmcp.services.chat.Attachment
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBubble
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Message menu")
class MessageMenuTest {
    @Test
    fun `a message the user wrote can be edited but not regenerated`() {
        val menu = menuFor(ChatItem.UserMessage("q", "你好"))
        assertTrue(MessageMenuAction.EDIT in menu)
        assertFalse(MessageMenuAction.REGENERATE in menu)
    }

    @Test
    fun `only an answer to a question offers regenerate`() {
        val items =
            ChatPresentation.toItems(
                listOf(
                    ChatBubble("assistant", "喵", id = "g"),
                    ChatBubble("user", "在吗", id = "q"),
                    ChatBubble("assistant", "在", id = "a"),
                ),
            )
        assertFalse(MessageMenuAction.REGENERATE in menuFor(items[0]))
        assertTrue(MessageMenuAction.REGENERATE in menuFor(items[2]))
        assertFalse(MessageMenuAction.EDIT in menuFor(items[2]))
    }

    @Test
    fun `the context entry flips with the state of the message`() {
        val item = ChatItem.UserMessage("q", "你好")
        assertTrue(MessageMenuAction.EXCLUDE in menuFor(item, excluded = false))
        assertFalse(MessageMenuAction.INCLUDE in menuFor(item, excluded = false))
        assertTrue(MessageMenuAction.INCLUDE in menuFor(item, excluded = true))
        assertFalse(MessageMenuAction.EXCLUDE in menuFor(item, excluded = true))
    }

    @Test
    fun `a picture alone offers neither copy nor open, a document offers open`() {
        val picture = ChatItem.UserMessage("p", "", attachment = Attachment(Attachment.IMAGE, "a.jpg", "/a.jpg"))
        val file = ChatItem.UserMessage("f", "", attachment = Attachment(Attachment.FILE, "a.pdf", "/a.pdf"))
        assertFalse(MessageMenuAction.COPY in menuFor(picture))
        assertFalse(MessageMenuAction.OPEN in menuFor(picture))
        assertTrue(MessageMenuAction.OPEN in menuFor(file))
    }

    @Test
    fun `a message with words can be read aloud, and stopped while it is being read`() {
        val item = ChatItem.AssistantMessage("a", "好")
        assertTrue(MessageMenuAction.READ in menuFor(item))
        assertTrue(MessageMenuAction.STOP_READING in menuFor(item, reading = true))
        assertFalse(MessageMenuAction.READ in menuFor(item, reading = true))
        val picture = ChatItem.UserMessage("p", "", attachment = Attachment(Attachment.IMAGE, "a.jpg", "/a.jpg"))
        assertFalse(MessageMenuAction.READ in menuFor(picture))
    }

    @Test
    fun `a message kept out of the context cannot be made a memory`() {
        val item = ChatItem.AssistantMessage("a", "好")
        assertTrue(MessageMenuAction.REMEMBER in menuFor(item))
        assertFalse(MessageMenuAction.REMEMBER in menuFor(item, excluded = true))
        val picture = ChatItem.UserMessage("p", "", attachment = Attachment(Attachment.IMAGE, "a.jpg", "/a.jpg"))
        assertFalse(MessageMenuAction.REMEMBER in menuFor(picture))
    }

    @Test
    fun `delete stays last and multi-select is always offered`() {
        val menu = menuFor(ChatItem.AssistantMessage("a", "好"))
        assertEquals(MessageMenuAction.DELETE, menu.last())
        assertTrue(MessageMenuAction.MULTI_SELECT in menu)
    }

    @Test
    fun `simple mode user text message gets exactly COPY and READ`() {
        val item = ChatItem.UserMessage("q", "你好")
        val menu = menuFor(item, simple = true)
        assertEquals(listOf(MessageMenuAction.COPY, MessageMenuAction.READ), menu)
    }

    @Test
    fun `simple mode assistant text message gets exactly COPY and READ`() {
        val item = ChatItem.AssistantMessage("a", "好")
        val menu = menuFor(item, simple = true)
        assertEquals(listOf(MessageMenuAction.COPY, MessageMenuAction.READ), menu)
    }

    @Test
    fun `simple mode with reading=true replaces READ with STOP_READING`() {
        val item = ChatItem.AssistantMessage("a", "好")
        val menu = menuFor(item, reading = true, simple = true)
        assertEquals(listOf(MessageMenuAction.COPY, MessageMenuAction.STOP_READING), menu)
    }

    @Test
    fun `simple mode text-only message without text gets empty list`() {
        val item = ChatItem.UserMessage("q", "")
        val menu = menuFor(item, simple = true)
        assertEquals(emptyList<MessageMenuAction>(), menu)
    }

    @Test
    fun `simple mode image-only message gets empty list`() {
        val picture = ChatItem.UserMessage("p", "", attachment = Attachment(Attachment.IMAGE, "a.jpg", "/a.jpg"))
        val menu = menuFor(picture, simple = true)
        assertEquals(emptyList<MessageMenuAction>(), menu)
    }

    @Test
    fun `simple mode file-only message gets empty list`() {
        val file = ChatItem.UserMessage("f", "", attachment = Attachment(Attachment.FILE, "a.pdf", "/a.pdf"))
        val menu = menuFor(file, simple = true)
        assertEquals(emptyList<MessageMenuAction>(), menu)
    }
}
