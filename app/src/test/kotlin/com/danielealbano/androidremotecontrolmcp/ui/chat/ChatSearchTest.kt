package com.danielealbano.androidremotecontrolmcp.ui.chat

import com.danielealbano.androidremotecontrolmcp.services.chat.Attachment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ChatSearch")
class ChatSearchTest {
    private val items =
        listOf(
            ChatItem.UserMessage("q1", "帮我查一下 Kotlin 协程"),
            ChatItem.AssistantMessage("a1", "**kotlin** 的协程是……"),
            ChatItem.UserMessage("q2", "", attachment = Attachment(Attachment.FILE, "Kotlin笔记.pdf", "/tmp/a.pdf")),
            ChatItem.PhoneTask("t1", request = "打开设置", taskId = "w1"),
            ChatItem.AssistantMessage("a2", "没有关系的一句话"),
        )

    @Test
    fun `occurrences are found ignoring case and never overlap`() {
        assertEquals(listOf(0..1, 5..6), ChatSearch.ranges("ab cdAB", "ab"))
        assertEquals(listOf(0..1, 2..3), ChatSearch.ranges("aaaa", "aa"))
        assertEquals(emptyList<IntRange>(), ChatSearch.ranges("abc", ""))
        assertEquals(emptyList<IntRange>(), ChatSearch.ranges("abc", "x"))
    }

    @Test
    fun `hits are the rows showing the word, oldest first, attachment names included`() {
        assertEquals(listOf("q1", "a1", "q2"), ChatSearch.hits(items, " kotlin "))
    }

    @Test
    fun `a task card is found by the request it shows`() {
        assertEquals(listOf("t1"), ChatSearch.hits(items, "打开设置"))
    }

    @Test
    fun `markdown markers are not searchable text`() {
        assertTrue(ChatSearch.hits(items, "**").isEmpty())
    }

    @Test
    fun `an empty word finds nothing`() {
        assertTrue(ChatSearch.hits(items, "   ").isEmpty())
    }

    @Test
    fun `stepping wraps around at both ends`() {
        assertEquals(1, ChatSearch.step(0, 3, forward = true))
        assertEquals(0, ChatSearch.step(2, 3, forward = true))
        assertEquals(2, ChatSearch.step(0, 3, forward = false))
        assertEquals(0, ChatSearch.step(0, 0, forward = true))
    }

    @Test
    fun `the back-to-bottom button appears three rows away from the end`() {
        assertFalse(BottomTracker.isFar(lastVisible = null, lastIndex = 40))
        assertFalse(BottomTracker.isFar(lastVisible = 38, lastIndex = 40))
        assertTrue(BottomTracker.isFar(lastVisible = 37, lastIndex = 40))
        assertFalse(BottomTracker.isFar(lastVisible = 0, lastIndex = 0))
    }
}
