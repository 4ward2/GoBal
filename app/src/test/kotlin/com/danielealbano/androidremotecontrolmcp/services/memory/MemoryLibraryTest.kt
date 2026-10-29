package com.danielealbano.androidremotecontrolmcp.services.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("MemoryLibrary")
class MemoryLibraryTest {
    @TempDir
    lateinit var dir: File

    private var now = 1_000L

    private fun library(guard: MemoryGuard = MemoryGuard()) = MemoryLibrary(dir, Dispatchers.Unconfined, guard) { now }

    /** A contact's memory files as the app stored them before there was a library. */
    private fun oldFolder(
        owner: String,
        vararg files: Pair<MemoryFile, String>,
    ) {
        val folder = File(dir, owner).apply { mkdirs() }
        File(folder, "index.json").writeText(Json.encodeToString(files.map { it.first }))
        files.forEach { (file, text) -> File(folder, "${file.id}.txt").writeText(text) }
    }

    private val manual = MemoryFile("f1", "手册.pdf", "application/pdf", 7, enabled = true, addedAt = 5, ocr = true)
    private val notes = MemoryFile("f2", "备忘", "text/plain", 3, enabled = false, addedAt = 6)

    @Test
    fun `old per-contact files are taken in whole, and the old folder is kept as a backup`() =
        runTest {
            oldFolder("abc", manual to "小鱼干在柜子里", notes to "别忘了")
            oldFolder("assistant", MemoryFile("f3", "家里", "text/plain", 2) to "住三楼")
            val entries = library().load().associateBy { it.id }
            assertEquals(setOf("f1", "f2", "f3"), entries.keys)
            val taken = entries.getValue("f1")
            assertEquals("abc" to "手册.pdf", taken.scope to taken.title)
            assertTrue(taken.ocr && taken.enabled)
            assertEquals(MemorySource.FILE, taken.source.kind)
            assertFalse(entries.getValue("f2").enabled)
            assertEquals("assistant", entries.getValue("f3").scope)
            assertEquals("小鱼干在柜子里", library().text("f1"))
            assertTrue(File(dir, "abc/index.json.migrated").isFile)
            assertFalse(File(dir, "abc/index.json").exists())
            assertEquals("小鱼干在柜子里", File(dir, "abc/f1.txt").readText(), "the old text is not removed")
            // A second start finds nothing left to take in and nothing doubled.
            assertEquals(3, library().load().size)
        }

    @Test
    fun `when the library cannot be written the old files stay as they are and are still read`() =
        runTest {
            oldFolder("abc", manual to "小鱼干在柜子里")
            // A plain file where the library's folder should be: nothing can be created inside it.
            File(dir, "library").writeText("in the way")
            val stuck = library()
            assertEquals(listOf("f1"), stuck.load().map { it.id })
            assertEquals("小鱼干在柜子里", stuck.text("f1"))
            assertTrue(File(dir, "abc/index.json").isFile, "not renamed: nothing was moved")
            assertTrue(stuck.select("abc", "小鱼干", window = 128_000).prompt.contains("小鱼干在柜子里"))
            // Once the way is clear the same files are taken in.
            File(dir, "library").delete()
            assertEquals(listOf("f1"), library().load().map { it.id })
            assertTrue(File(dir, "abc/index.json.migrated").isFile)
        }

    @Test
    fun `an old folder whose index is unreadable is left alone`() =
        runTest {
            File(dir, "abc").mkdirs()
            File(dir, "abc/index.json").writeText("{ not json")
            File(dir, "abc/f1.txt").writeText("内容")
            assertTrue(library().load().isEmpty())
            assertEquals("内容", File(dir, "abc/f1.txt").readText())
        }

    @Test
    fun `memories are written, corrected, switched off, merged and deleted`() =
        runTest {
            val library = library()
            val first = library.add(NewMemory("妈妈不吃香菜\n也不吃辣")).getOrThrow()
            assertEquals("妈妈不吃香菜", first.title)
            assertEquals(MemoryEntry.GLOBAL, first.scope)
            now = 2_000L
            val second = library.add(NewMemory("爸爸爱喝茶", scope = "abc")).getOrThrow()
            assertEquals(listOf(second.id, first.id), library.entries.value.map { it.id }, "newest first")

            now = 3_000L
            val corrected = library.edit(first.id, MemoryEdit(text = "妈妈不吃香菜，能吃一点辣")).getOrThrow()
            assertEquals(3_000L, corrected.updatedAt)
            assertEquals("妈妈不吃香菜，能吃一点辣", library().text(first.id))
            val off = library.edit(first.id, MemoryEdit(enabled = false)).getOrThrow()
            assertFalse(off.enabled)
            assertEquals(3_000L, off.updatedAt, "switching off is not a change of what is remembered")
            assertEquals("abc", library.edit(first.id, MemoryEdit(scope = "abc")).getOrThrow().scope)

            val merged = library.merge(listOf(first.id, second.id)).getOrThrow()
            assertEquals("妈妈不吃香菜，能吃一点辣\n\n爸爸爱喝茶", library.text(merged.id))
            assertEquals("abc", merged.scope)
            assertEquals(listOf(merged.id), library().load().map { it.id })
            library.remove(setOf(merged.id))
            assertTrue(library().load().isEmpty())
            assertTrue(library.merge(emptyList()).isFailure)
        }

    @Test
    fun `merging memories of different contacts gives one that everyone gets`() =
        runTest {
            val library = library()
            val a = library.add(NewMemory("甲", scope = "abc")).getOrThrow()
            val b = library.add(NewMemory("乙", scope = "xyz")).getOrThrow()
            assertEquals(MemoryEntry.GLOBAL, library.merge(listOf(a.id, b.id)).getOrThrow().scope)
        }

    @Test
    fun `keys and verification codes are refused, vault values are kept as their placeholders`() =
        runTest {
            val guarded = library(MemoryGuard { it.replace("13800138000", "‹vault:手机号›") })
            val key = guarded.add(NewMemory("我的密钥是 sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789"))
            assertEquals(MemoryGuard.HAS_KEY, key.exceptionOrNull()?.message)
            val code = guarded.add(NewMemory("验证码是 482913，五分钟内有效"))
            assertEquals(MemoryGuard.HAS_CODE, code.exceptionOrNull()?.message)
            assertTrue(guarded.load().isEmpty())
            val phone = guarded.add(NewMemory("我的手机号是 13800138000")).getOrThrow()
            assertEquals("我的手机号是 ‹vault:手机号›", guarded.text(phone.id))
            val edited = guarded.edit(phone.id, MemoryEdit(text = "OTP: 123456"))
            assertEquals(MemoryGuard.HAS_CODE, edited.exceptionOrNull()?.message)
            assertEquals("我的手机号是 ‹vault:手机号›", guarded.text(phone.id), "a refused edit changes nothing")
        }

    @Test
    fun `a request carries what is in scope and enabled, and using a memory is remembered`() =
        runTest {
            val library = library()
            val everyone = library.add(NewMemory("家住三楼")).getOrThrow()
            val mine = library.add(NewMemory("小夏爱吃小鱼干", scope = "abc")).getOrThrow()
            library.add(NewMemory("老张血压高", scope = "xyz"))
            val off = library.add(NewMemory("这条关掉了")).getOrThrow()
            library.edit(off.id, MemoryEdit(enabled = false))
            now = 9_000L
            val chosen = library.select("abc", "", window = 128_000)
            assertEquals(setOf(everyone.id, mine.id), chosen.used.map { it.entry.id }.toSet())
            assertTrue(chosen.left.isEmpty())
            assertTrue(chosen.prompt.startsWith(MemorySelector.HEADER))
            val used = library().load().associateBy { it.id }
            assertEquals(9_000L, used.getValue(mine.id).lastUsedAt)
            assertEquals(0L, used.getValue(off.id).lastUsedAt)
            now = 10_000L
            val before = library.entries.value.map { it.lastUsedAt }
            assertEquals(chosen.used.size, library.select("abc", "", window = 128_000, mark = false).used.size)
            assertEquals(before, library.entries.value.map { it.lastUsedAt })
            val stranger = library.select("nobody", "", window = 128_000)
            assertEquals(listOf(everyone.id), stranger.used.map { it.entry.id })
        }
}

@DisplayName("MemorySelector")
class MemorySelectorTest {
    private fun entry(
        id: String,
        usedAt: Long = 0,
    ) = MemoryEntry(id, id, updatedAt = 1, lastUsedAt = usedAt)

    private val day = 86_400_000L

    @Test
    fun `the budget follows the model's window, within sensible bounds`() {
        assertEquals(400, MemorySelector.budget(2_000))
        assertEquals(4_800, MemorySelector.budget(32_000))
        assertEquals(8_000, MemorySelector.budget(1_000_000))
    }

    @Test
    fun `what answers the question goes first, and what does not fit is listed as left out`() {
        val diet = entry("饮食") to "妈妈不吃香菜，爸爸不吃辣。".repeat(20)
        val house = entry("房子") to "家住三楼，门牌 302。".repeat(20)
        val work = entry("工作") to "周三下午三点开周会。".repeat(20)
        val chosen = MemorySelector.select(listOf(house, work, diet), "妈妈吃不吃香菜", budget = 420, now = day)
        assertEquals(
            "饮食",
            chosen.used
                .first()
                .entry.id,
        )
        assertFalse(chosen.used.first().cut)
        assertTrue(chosen.left.isNotEmpty(), "three of 260 tokens do not fit 420")
        assertTrue(chosen.tokens <= 420)
        assertTrue(chosen.prompt.contains("《饮食》"))
        assertEquals(3, chosen.used.size + chosen.left.size)
    }

    @Test
    fun `a memory too long for what is left is sent cut and marked, not dropped whole`() {
        val long = entry("长文") to "这是一段很长的资料。".repeat(200)
        val chosen = MemorySelector.select(listOf(long), "", budget = 500, now = day)
        val pick = chosen.used.single()
        assertTrue(pick.cut && pick.tokens <= 500)
        assertTrue(pick.text.endsWith("…（已截断）"))
        assertTrue(chosen.left.isEmpty())
    }

    @Test
    fun `with nothing asked, what was used lately goes first, and blank memories never go`() {
        val old = entry("旧", usedAt = 1) to "旧资料"
        val fresh = entry("新", usedAt = 10 * day) to "新资料"
        val blank = entry("空") to "   "
        val chosen = MemorySelector.select(listOf(old, blank, fresh), "", budget = 400, now = 10 * day)
        assertEquals(listOf("新", "旧"), chosen.used.map { it.entry.id })
        assertEquals("", MemorySelector.select(listOf(blank), "x", 400, day).prompt)
    }

    @Test
    fun `english and mixed text are matched too`() {
        val wifi = entry("wifi") to "The home Wi-Fi password hint is on the fridge."
        val car = entry("car") to "The car is serviced every March."
        val chosen = MemorySelector.select(listOf(car, wifi), "where is the wifi password", budget = 400, now = day)
        assertEquals(
            "wifi",
            chosen.used
                .first()
                .entry.id,
        )
    }
}
