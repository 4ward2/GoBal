package com.danielealbano.androidremotecontrolmcp.services.skill

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("FileSkillRepository")
class FileSkillRepositoryTest {
    @TempDir
    lateinit var dir: File

    private var now = 1_000L

    private fun repo() = FileSkillRepository(File(dir, "skills/skills.json"), Dispatchers.Unconfined) { now }

    private val skill =
        Skill(
            name = "点{{店名}}外卖",
            params = listOf(SkillParam("店名", "蜜雪冰城")),
            steps =
                listOf(
                    SkillStep.Launch("com.sankuai.meituan", "imeituan://www.meituan.com/search"),
                    SkillStep.Tap(NodeSelector(text = "{{店名}}", match = TextMatch.CONTAINS, resourceId = "a:id/b")),
                    SkillStep.Type(null, "{{店名}}"),
                    SkillStep.Verify("{{店名}}", ocr = false, timeoutMs = 3_000),
                    SkillStep.Wait(NodeSelector(className = "EditText")),
                    SkillStep.Scroll(SkillScrollDirection.UP),
                    SkillStep.Back,
                    SkillStep.HandOver("付款"),
                ),
            source = SkillSource.AGENT,
        )

    @Test
    fun `every step type survives a save and a reload`() =
        runTest {
            repo().save(skill, replace = false).getOrThrow()
            val loaded = repo().also { it.load() }.find("点{{店名}}外卖")
            assertEquals(skill.copy(createdAt = 1_000, updatedAt = 1_000), loaded)
        }

    @Test
    fun `a second skill with the same name is refused unless replacing`() =
        runTest {
            val r = repo()
            r.save(skill, replace = false).getOrThrow()
            val changed = skill.copy(description = "新的")
            assertTrue(r.save(changed, replace = false).isFailure)
            // Saving an identical skill again is not an error (retries are safe).
            assertTrue(r.save(skill, replace = false).isSuccess)
            now = 2_000
            val replaced = r.save(changed, replace = true).getOrThrow()
            assertEquals(1_000, replaced.createdAt)
            assertEquals(2_000, replaced.updatedAt)
            assertEquals(1, r.skills.value.size)
        }

    @Test
    fun `names are compared without case, spaces or punctuation`() =
        runTest {
            val r = repo()
            r.save(skill.copy(name = "Order Tea"), replace = false).getOrThrow()
            assertTrue(r.save(skill.copy(name = "order tea!", description = "x"), replace = false).isFailure)
            assertEquals("Order Tea", r.find("ORDER  TEA")?.name)
        }

    @Test
    fun `renaming keeps run statistics and cannot overwrite another skill`() =
        runTest {
            val r = repo()
            r.save(skill.copy(name = "A"), replace = false).getOrThrow()
            r.save(skill.copy(name = "B"), replace = false).getOrThrow()
            r.recordRun("A", ok = true)
            assertTrue(r.save(skill.copy(name = "B"), replace = true, previousName = "A").isFailure)
            val renamed = r.save(skill.copy(name = "C"), replace = true, previousName = "A").getOrThrow()
            assertEquals(1, renamed.runCount)
            assertEquals(listOf("B", "C"), r.skills.value.map { it.name })
        }

    @Test
    fun `invalid skills are not stored`() =
        runTest {
            val r = repo()
            assertTrue(r.save(skill.copy(steps = emptyList()), replace = false).isFailure)
            assertTrue(r.skills.value.isEmpty())
        }

    @Test
    fun `delete and recordRun`() =
        runTest {
            val r = repo()
            r.save(skill, replace = false).getOrThrow()
            r.recordRun(skill.name, ok = false)
            assertEquals(false, r.find(skill.name)?.lastRunOk)
            assertTrue(r.delete("点{{店名}}外卖"))
            assertFalse(r.delete("点{{店名}}外卖"))
            assertNull(repo().also { it.load() }.find(skill.name))
        }

    @Test
    fun `an unreadable file is set aside instead of being overwritten`() =
        runTest {
            val file =
                File(dir, "skills/skills.json").apply {
                    parentFile?.mkdirs()
                    writeText("{not json")
                }
            val r = repo()
            r.load()
            assertTrue(r.skills.value.isEmpty())
            assertTrue(File(file.parentFile, "skills.json.broken").isFile)
        }
}
