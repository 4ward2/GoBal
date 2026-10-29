package com.danielealbano.androidremotecontrolmcp.services.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("ConversationFiles")
class ConversationFilesTest {
    @TempDir
    lateinit var dir: File

    private var clock = 1_000L
    private val owner = "abc"
    private val old =
        listOf(
            ChatBubble("assistant", "喵", id = "g"),
            ChatBubble("user", "今天吃什么\n随便说说", id = "q1", excluded = true),
            ChatBubble("assistant", "小鱼干", id = "a1"),
        )

    private fun files() = ConversationFiles(dir) { clock }

    private fun legacy(name: String = "chat_abc.json") = File(dir, name)

    private fun writeLegacy(
        messages: List<ChatBubble> = old,
        name: String = "chat_abc.json",
    ) = legacy(name).apply { writeText(ConversationCodec.encode(messages)) }

    private fun stored(id: String = ConversationFiles.MAIN) = File(dir, "conversations/$owner/$id.json")

    @Nested
    @DisplayName("bringing over the old single history")
    inner class Migration {
        @Test
        fun `the old history becomes the main conversation, titled after its first question`() {
            writeLegacy()
            val index = files().index(owner)
            assertEquals(ConversationFiles.MAIN, index.active)
            assertEquals(listOf("今天吃什么"), index.conversations.map { it.title })
            assertEquals(old, files().load(owner, ConversationFiles.MAIN))
        }

        @Test
        fun `the old file is kept as a backup, not deleted`() {
            val original = writeLegacy().readText()
            files().index(owner)
            assertFalse(legacy().exists())
            assertEquals(original, File(dir, "chat_abc.json.migrated").readText())
            assertEquals(original, stored().readText())
        }

        @Test
        fun `the assistant's history is brought over from its own file`() {
            writeLegacy(listOf(ChatBubble("user", "你好", id = "x")), "native_chat_history.json")
            val files = files()
            val assistant = ConversationFiles.ASSISTANT
            assertEquals(listOf("你好"), files.load(assistant, files.index(assistant).active).map { it.content })
            assertTrue(File(dir, "native_chat_history.json.migrated").isFile)
        }

        @Test
        fun `migrating twice changes nothing`() {
            writeLegacy()
            val first = files().index(owner)
            val second = files().index(owner)
            assertEquals(first, second)
            assertEquals(old, files().load(owner, ConversationFiles.MAIN))
        }

        @Test
        fun `a contact with no history starts with one empty conversation and nothing on disk`() {
            val index = files().index(owner)
            assertEquals(listOf(ConversationFiles.MAIN), index.conversations.map { it.id })
            assertTrue(files().load(owner, ConversationFiles.MAIN).isEmpty())
            assertFalse(File(dir, "conversations").exists())
        }

        @Test
        fun `an unreadable old file is left exactly where it is and a copy is set aside`() {
            val broken = "[{\"role\":\"user\",\"content\":\"重要的话"
            legacy().writeText(broken)
            val files = files()
            files.index(owner)
            assertTrue(files.load(owner, ConversationFiles.MAIN).isEmpty())
            assertEquals(broken, legacy().readText())
            assertEquals(broken, File(dir, "conversations/$owner/main.json.broken").readText())
            // Carrying on in the new conversation still does not touch the old file.
            files.save(owner, ConversationFiles.MAIN, listOf(ChatBubble("user", "新的", id = "n")))
            assertEquals(broken, legacy().readText())
            assertEquals(listOf("新的"), files().load(owner, ConversationFiles.MAIN).map { it.content })
        }

        @Test
        fun `when the new place cannot be written the old history stays and is still what loads`() {
            writeLegacy()
            // A file where the conversations directory should be: every write below it fails.
            File(dir, "conversations").writeText("in the way")
            val files = files()
            assertEquals(listOf("今天吃什么"), files.index(owner).conversations.map { it.title })
            assertEquals(old, files.load(owner, ConversationFiles.MAIN))
            assertTrue(legacy().isFile)
            assertFalse(File(dir, "chat_abc.json.migrated").exists())
            // Once the obstacle is gone the next start finishes the job.
            File(dir, "conversations").delete()
            files().index(owner)
            assertEquals(old, files().load(owner, ConversationFiles.MAIN))
            assertTrue(File(dir, "chat_abc.json.migrated").isFile)
        }

        @Test
        fun `a crash after the copy but before the index loses nothing`() {
            val original = writeLegacy().readText()
            // The copy is written whole or not at all; here it was, and the index never followed.
            stored().apply { parentFile?.mkdirs() }.writeText(original)
            val files = files()
            assertEquals(old, files.load(owner, files.index(owner).active))
            assertEquals(listOf("今天吃什么"), files.index(owner).conversations.map { it.title })
        }
    }

    @Nested
    @DisplayName("storing conversations")
    inner class Storing {
        @Test
        fun `a long conversation is stored whole`() {
            val messages = (1..500).map { ChatBubble(if (it % 2 == 1) "user" else "assistant", "第${it}条", id = "m$it") }
            files().save(owner, "c1", messages)
            assertEquals(messages, files().load(owner, "c1"))
        }

        @Test
        fun `saving keeps the title and the last-used time current`() {
            val files = files()
            files.save(owner, "c1", listOf(ChatBubble("assistant", "喵", id = "g")))
            assertEquals("", files.index(owner).find("c1")?.title)
            clock = 5_000L
            val greeting = ChatBubble("assistant", "喵", id = "g")
            files.save(owner, "c1", listOf(greeting, ChatBubble("user", "讲个故事", id = "q")))
            val meta = files.index(owner).find("c1")
            assertEquals("讲个故事", meta?.title)
            assertEquals(5_000L, meta?.updatedAt)
        }

        @Test
        fun `a name the user gave survives later saves`() {
            val files = files()
            files.save(owner, "c1", listOf(ChatBubble("user", "原来的问题", id = "q")))
            val index = files.index(owner)
            files.writeIndex(owner, index.replacing(index.find("c1")!!.copy(title = "旅行计划", named = true)))
            files.save(owner, "c1", listOf(ChatBubble("user", "改过的问题", id = "q")))
            assertEquals("旅行计划", files.index(owner).find("c1")?.title)
        }

        @Test
        fun `pinned conversations come first, then the most recently used`() {
            val index =
                ConversationIndex(
                    "a",
                    listOf(
                        ConversationMeta("a", updatedAt = 3),
                        ConversationMeta("b", updatedAt = 9),
                        ConversationMeta("c", pinned = true, updatedAt = 1),
                    ),
                )
            assertEquals(listOf("c", "b", "a"), index.sorted().map { it.id })
        }

        @Test
        fun `an unreadable conversation is set aside instead of being lost to the next save`() {
            val files = files()
            files.save(owner, "c1", listOf(ChatBubble("user", "好的", id = "q")))
            stored("c1").writeText("{not json")
            assertTrue(files.load(owner, "c1").isEmpty())
            files.save(owner, "c1", listOf(ChatBubble("user", "新的", id = "n")))
            assertEquals("{not json", File(dir, "conversations/$owner/c1.json.broken").readText())
        }

        @Test
        fun `a lost index is rebuilt from the conversations on disk`() {
            val files = files()
            files.save(owner, "c1", listOf(ChatBubble("user", "第一段", id = "q1")))
            files.save(owner, "c2", listOf(ChatBubble("user", "第二段", id = "q2")))
            File(dir, "conversations/$owner/index.json").writeText("garbage")
            val rebuilt = files().index(owner)
            val listed = rebuilt.conversations
            assertEquals(setOf("c1", "c2"), listed.map { it.id }.toSet())
            assertEquals(setOf("第一段", "第二段"), listed.map { it.title }.toSet())
            assertEquals(listOf("第一段"), files().load(owner, "c1").map { it.content })
        }

        @Test
        fun `an index pointing at a conversation that is gone opens another one`() {
            val files = files()
            files.save(owner, "c1", listOf(ChatBubble("user", "在", id = "q")))
            files.writeIndex(owner, files.index(owner).copy(active = "nowhere"))
            val index = files.index(owner)
            assertEquals("c1", index.active)
            assertTrue(index.find(index.active) != null)
        }

        @Test
        fun `deleting an owner removes its conversations and its old backup`() {
            writeLegacy()
            val files = files()
            files.index(owner)
            files.save(owner, "c1", listOf(ChatBubble("user", "在", id = "q")))
            files.deleteOwner(owner)
            assertFalse(File(dir, "conversations/$owner").exists())
            assertFalse(File(dir, "chat_abc.json.migrated").exists())
            assertEquals(emptyList<String>(), files.owners())
        }
    }
}
