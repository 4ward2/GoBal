package com.danielealbano.androidremotecontrolmcp.services.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("FileMemoryRepository")
class MemoryRepositoryTest {
    @TempDir
    lateinit var dir: File

    private fun repo() = FileMemoryRepository(dir, Dispatchers.Unconfined, clock = { 42L })

    @Test
    fun `a file's text can be read back and corrected, and the prompt carries the correction`() =
        runTest {
            val repo = repo()
            val file = repo.add("abc", "化验单.jpg", "image/jpeg", "血压 l28/82", ocr = true)
            assertEquals("血压 l28/82", repo.text("abc", file.id))
            repo.updateText("abc", file.id, "血压 128/82")
            assertEquals("血压 128/82", repo().text("abc", file.id))
            val listed =
                repo
                    .files("abc")
                    .first()
                    .single()
            assertEquals("血压 128/82".length, listed.chars)
            assertTrue(listed.ocr)
            assertTrue(repo.context("abc").contains("128/82"))
            repo.updateText("abc", "no-such-file", "x")
            assertEquals("", repo.text("abc", "no-such-file"))
            assertEquals(1, repo.files("abc").first().size)
        }

    @Test
    fun `a handwritten note is named after its first words`() {
        assertEquals("妈妈不吃香菜", MemoryNotes.title("\n  妈妈不吃香菜\n也不吃辣"))
        assertEquals("每周三下午三点要去社区医院量血压…", MemoryNotes.title("每周三下午三点要去社区医院量血压，记得带医保卡"))
        assertEquals("", MemoryNotes.title("   "))
    }

    @Test
    fun `files are stored per owner, toggled, and listed after a reload`() =
        runTest {
            val repo = repo()
            val a = repo.add("abc", "手册.pdf", "application/pdf", "第一章 内容", ocr = true)
            repo.add("other", "别人的.txt", "text/plain", "x")
            assertEquals(listOf(a), repo.files("abc").first())
            assertTrue(a.ocr)
            assertEquals("第一章 内容".length, a.chars)

            repo.setEnabled("abc", a.id, false)
            assertFalse(
                repo
                    .files("abc")
                    .first()
                    .single()
                    .enabled,
            )

            val again = repo()
            assertEquals(listOf(a.copy(enabled = false)), again.files("abc").first())
            assertEquals("", again.context("abc"))
        }

    @Test
    fun `the prompt section holds enabled files only and removal forgets the text`() =
        runTest {
            val repo = repo()
            val a = repo.add("abc", "a.txt", "text/plain", "苹果")
            repo.add("abc", "b.txt", "text/plain", "香蕉")
            val context = repo.context("abc")
            assertTrue(context.contains("《a.txt》\n苹果"))
            assertTrue(context.contains("《b.txt》\n香蕉"))

            repo.remove("abc", a.id)
            assertFalse(repo.context("abc").contains("苹果"))
            assertFalse(File(File(dir, "abc"), "${a.id}.txt").exists())

            repo.removeOwner("abc")
            assertTrue(repo.files("abc").first().isEmpty())
        }

    @Test
    fun `the budget is shared between files and long ones are cut`() {
        val files =
            listOf(
                MemoryFile("1", "a") to "a".repeat(100),
                MemoryFile("2", "b") to "b".repeat(10),
                MemoryFile("3", "empty") to "   ",
            )
        val text = MemoryContext.compose(files, budgetChars = 40)
        assertTrue(text.startsWith("【记忆资料】"))
        assertTrue(text.contains("《a》\n" + "a".repeat(20) + "\n…（已截断）"))
        assertTrue(text.contains("《b》\n" + "b".repeat(10)))
        assertFalse(text.contains("empty"))
        assertEquals("", MemoryContext.compose(emptyList(), 100))
    }
}
