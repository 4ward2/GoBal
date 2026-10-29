package com.danielealbano.androidremotecontrolmcp.services.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("ConversationCatalog")
class ConversationCatalogTest {
    @TempDir
    lateinit var dir: File

    private var clock = 1_000L
    private var ids = 0
    private val owner = "abc"
    private val files by lazy { ConversationFiles(dir) { clock } }
    private val catalog by lazy { ConversationCatalog(files, { "c${++ids}" }, { clock }) }

    private fun say(
        id: String,
        vararg lines: String,
    ) {
        clock += 1_000
        val messages =
            lines.mapIndexed { i, text -> ChatBubble(if (i % 2 == 0) "user" else "assistant", text, id = "$id-$i") }
        files.save(owner, id, messages)
    }

    @Test
    fun `starting a conversation opens it and tells the chat to reload`() {
        say(ConversationFiles.MAIN, "第一段的问题", "回答")
        val before = catalog.revision.value
        val fresh = catalog.create(owner)
        assertEquals("c1", fresh.id)
        assertEquals("c1", catalog.activeId(owner))
        assertEquals(before + 1, catalog.revision.value)
        assertEquals(2, catalog.list(owner).size)
    }

    @Test
    fun `an unused conversation is reused instead of piling up empty ones`() {
        say(ConversationFiles.MAIN, "第一段的问题", "回答")
        catalog.create(owner)
        catalog.open(owner, ConversationFiles.MAIN)
        assertEquals("c1", catalog.create(owner).id)
        assertEquals(2, catalog.list(owner).size)
    }

    @Test
    fun `a conversation holding only a character's greeting counts as unused`() {
        files.save(owner, ConversationFiles.MAIN, listOf(ChatBubble("assistant", "喵", id = "g")))
        assertEquals(ConversationFiles.MAIN, catalog.create(owner).id)
        assertEquals(1, catalog.list(owner).size)
    }

    @Test
    fun `opening remembers a message to jump to and hands it out once`() {
        say(ConversationFiles.MAIN, "问", "答")
        say("c9", "另一段", "回答")
        catalog.open(owner, "c9", jumpTo = "c9-1")
        assertEquals("c9", catalog.activeId(owner))
        assertEquals("c9-1", catalog.takeJump())
        assertNull(catalog.takeJump())
    }

    @Test
    fun `opening a conversation that does not exist does nothing`() {
        say(ConversationFiles.MAIN, "问", "答")
        val before = catalog.revision.value
        catalog.open(owner, "nowhere")
        assertEquals(ConversationFiles.MAIN, catalog.activeId(owner))
        assertEquals(before, catalog.revision.value)
    }

    @Test
    fun `renaming sticks, and a blank name goes back to the first question`() {
        say(ConversationFiles.MAIN, "去哪里旅行", "云南")
        catalog.rename(owner, ConversationFiles.MAIN, "  旅行计划  ")
        assertEquals("旅行计划", catalog.list(owner).single().title)
        say(ConversationFiles.MAIN, "去哪里旅行", "云南", "还有呢", "四川")
        assertEquals("旅行计划", catalog.list(owner).single().title)
        catalog.rename(owner, ConversationFiles.MAIN, " ")
        assertEquals("去哪里旅行", catalog.list(owner).single().title)
        assertFalse(catalog.list(owner).single().named)
    }

    @Test
    fun `a pinned conversation is listed first`() {
        say(ConversationFiles.MAIN, "老的", "答")
        say("c9", "新的", "答")
        assertEquals(listOf("c9", ConversationFiles.MAIN), catalog.list(owner).map { it.id })
        catalog.setPinned(owner, ConversationFiles.MAIN, true)
        assertEquals(listOf(ConversationFiles.MAIN, "c9"), catalog.list(owner).map { it.id })
    }

    @Test
    fun `deleting the open conversation opens the next and removes its messages`() {
        say(ConversationFiles.MAIN, "老的", "答")
        say("c9", "新的", "答")
        catalog.open(owner, "c9")
        val before = catalog.revision.value
        catalog.delete(owner, "c9")
        assertEquals(ConversationFiles.MAIN, catalog.activeId(owner))
        assertEquals(listOf(ConversationFiles.MAIN), catalog.list(owner).map { it.id })
        assertFalse(File(dir, "conversations/$owner/c9.json").exists())
        assertEquals(before + 1, catalog.revision.value)
    }

    @Test
    fun `deleting another conversation leaves the open one alone`() {
        say(ConversationFiles.MAIN, "老的", "答")
        say("c9", "新的", "答")
        catalog.open(owner, ConversationFiles.MAIN)
        val before = catalog.revision.value
        catalog.delete(owner, "c9")
        assertEquals(ConversationFiles.MAIN, catalog.activeId(owner))
        assertEquals(before, catalog.revision.value)
        assertEquals(listOf("老的", "答"), files.load(owner, ConversationFiles.MAIN).map { it.content })
    }

    @Test
    fun `deleting the last conversation leaves a fresh empty one`() {
        say(ConversationFiles.MAIN, "唯一的", "答")
        catalog.delete(owner, ConversationFiles.MAIN)
        assertEquals(listOf("c1"), catalog.list(owner).map { it.id })
        assertEquals("c1", catalog.activeId(owner))
        assertTrue(files.load(owner, "c1").isEmpty())
    }

    @Test
    fun `search finds messages in every contact's conversations with the words around them`() {
        say(ConversationFiles.MAIN, "周末去**爬山**怎么样", "好主意")
        say("c9", "另一段", "上次爬山很累，这次换个轻松的")
        clock += 1_000
        val asked = listOf(ChatBubble("user", "帮我查爬山路线", id = "z"))
        files.save(ConversationFiles.ASSISTANT, ConversationFiles.MAIN, asked)
        val hits = catalog.search(" 爬山 ")
        assertEquals(
            listOf(ConversationFiles.ASSISTANT to "z", owner to "c9-1", owner to "main-0"),
            hits.map { it.owner to it.messageId },
        )
        assertEquals("周末去爬山怎么样", hits.last().snippet)
        assertEquals("另一段", hits[1].conversationTitle)
        assertTrue(catalog.search("   ").isEmpty())
        assertTrue(catalog.search("不存在的词").isEmpty())
    }

    @Test
    fun `a snippet is cut around the match in a long message`() {
        val text = "前".repeat(50) + "关键词" + "后".repeat(80)
        val snippet = ConversationSearch.snippet(text, "关键词")!!
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("关键词"))
        assertTrue(snippet.length < 80)
    }
}
