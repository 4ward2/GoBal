package com.danielealbano.androidremotecontrolmcp.services.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ConversationEdits")
class ConversationEditsTest {
    private val q1 = ChatBubble("user", "问一", id = "q1")
    private val a1 = ChatBubble("assistant", "答一", id = "a1")
    private val q2 = ChatBubble("user", "问二", id = "q2")
    private val a2 = ChatBubble("assistant", "答二", id = "a2")
    private val all = listOf(q1, a1, q2, a2)

    @Test
    fun `editing a question keeps what came before and counts what would be dropped`() {
        val rewind = ConversationEdits.forEdit(all, "q2")
        assertEquals(listOf(q1, a1), rewind?.kept)
        assertEquals(q2, rewind?.target)
        assertEquals(1, rewind?.dropped)
        assertEquals(3, ConversationEdits.forEdit(all, "q1")?.dropped)
    }

    @Test
    fun `only messages the user wrote can be edited`() {
        assertNull(ConversationEdits.forEdit(all, "a1"))
        assertNull(ConversationEdits.forEdit(all, "missing"))
        val card = ChatBubble("user", "步骤", id = "s", skill = "技能")
        assertNull(ConversationEdits.forEdit(listOf(card), "s"))
    }

    @Test
    fun `regenerating keeps the question and drops the reply and everything after it`() {
        val last = ConversationEdits.forRegenerate(all, "a2")
        assertEquals(listOf(q1, a1, q2), last?.kept)
        assertEquals(0, last?.dropped)
        val earlier = ConversationEdits.forRegenerate(all, "a1")
        assertEquals(listOf(q1), earlier?.kept)
        assertEquals(2, earlier?.dropped)
    }

    @Test
    fun `a reply that answers no question cannot be regenerated`() {
        val greeting = ChatBubble("assistant", "喵", id = "g")
        assertFalse(ConversationEdits.canRegenerate(listOf(greeting, q1, a1), 0))
        assertTrue(ConversationEdits.canRegenerate(listOf(greeting, q1, a1), 2))
        assertNull(ConversationEdits.forRegenerate(all, "q1"))
        val twoReplies = listOf(q1, a1, a2)
        assertNull(ConversationEdits.forRegenerate(twoReplies, "a2"))
    }

    @Test
    fun `a reply to a question left out of the context cannot be regenerated`() {
        val hidden = listOf(q1.copy(excluded = true), a1)
        assertNull(ConversationEdits.forRegenerate(hidden, "a1"))
    }

    @Test
    fun `leaving a question out takes its answer with it`() {
        val marked = ConversationEdits.setExcluded(all, setOf("q1"), excluded = true)
        assertEquals(listOf(true, true, false, false), marked.map { it.excluded })
    }

    @Test
    fun `leaving an answer out touches the answer alone`() {
        val marked = ConversationEdits.setExcluded(all, setOf("a1"), excluded = true)
        assertEquals(listOf(false, true, false, false), marked.map { it.excluded })
    }

    @Test
    fun `a question followed by another question has no answer to take along`() {
        val messages = listOf(q1, q2, a2)
        val marked = ConversationEdits.setExcluded(messages, setOf("q1"), excluded = true)
        assertEquals(listOf(true, false, false), marked.map { it.excluded })
    }

    @Test
    fun `putting a message back touches that message alone`() {
        val hidden = all.map { it.copy(excluded = true) }
        val back = ConversationEdits.setExcluded(hidden, setOf("q1"), excluded = false)
        assertEquals(listOf(false, true, true, true), back.map { it.excluded })
    }

    @Test
    fun `several messages are marked in one go`() {
        val marked = ConversationEdits.setExcluded(all, setOf("q1", "q2"), excluded = true)
        assertTrue(marked.all { it.excluded })
    }

    @Test
    fun `messages left out are not sent to the model`() {
        val marked = ConversationEdits.setExcluded(all, setOf("q1"), excluded = true)
        val sent = OutgoingMessages.toModelMessages("系统", marked)
        assertEquals(listOf("系统", "问二", "答二"), sent.map { it.text })
    }
}
