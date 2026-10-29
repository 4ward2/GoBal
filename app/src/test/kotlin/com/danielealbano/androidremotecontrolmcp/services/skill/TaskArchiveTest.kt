package com.danielealbano.androidremotecontrolmcp.services.skill

import com.danielealbano.androidremotecontrolmcp.services.skill.SkillTestTrees.Node
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillTestTrees.window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("Archiving tasks as skills")
class TaskArchiveTest {
    private val mt = "com.sankuai.meituan"

    private val done =
        listOf(
            SkillStep.Launch(mt),
            SkillStep.Tap(NodeSelector(resourceId = "$mt:id/search_layout_area", packageName = mt)),
            SkillStep.Type(NodeSelector(resourceId = "$mt:id/search_edit_text", packageName = mt), "蜜"),
            SkillStep.Type(NodeSelector(resourceId = "$mt:id/search_edit_text", packageName = mt), "蜜雪冰城"),
            SkillStep.Tap(NodeSelector(text = "蜜雪冰城(西电东区店)", className = "android.view.ViewGroup", packageName = mt)),
        )

    @Nested
    @DisplayName("TaskArchive")
    inner class Archive {
        @Test
        fun `a tap that only waves a popup away becomes one that is made only if the popup shows`() {
            val steps =
                listOf(
                    SkillStep.Launch(mt),
                    SkillStep.Tap(NodeSelector(text = "暂不升级", packageName = mt)),
                    SkillStep.Tap(NodeSelector(text = "拼好饭", packageName = mt)),
                    SkillStep.Tap(NodeSelector(text = "取消", packageName = mt)),
                )
            val skill = TaskArchive.build("看看拼好饭", steps, emptyList(), emptyMap()).getOrThrow()
            val taps = skill.steps.filterIsInstance<SkillStep.Tap>()
            assertEquals(listOf(true, false, false), taps.map { it.optional })
            assertEquals(listOf(false, true, true), taps.map { it.scroll })
        }

        @Test
        fun `a successful task becomes a parameterised skill with a checkpoint`() {
            val skill = TaskArchive.build("帮我点蜜雪冰城外卖", done, emptyList(), mapOf("店名" to "蜜雪冰城")).getOrThrow()
            assertEquals("帮我点{{店名}}外卖", skill.name)
            assertEquals(SkillSource.AGENT, skill.source)
            // Typing into one field twice keeps the final text only.
            assertEquals(5, skill.steps.size)
            assertEquals("{{店名}}", (skill.steps[2] as SkillStep.Type).text)
            assertEquals(TextMatch.CONTAINS, (skill.steps[3] as SkillStep.Tap).target.match)
            assertEquals(SkillStep.Verify("{{店名}}"), skill.steps[4])
            // The archived name still matches the next request with another shop.
            assertEquals(mapOf("店名" to "瑞幸"), SkillMatcher.match("点瑞幸外卖", listOf(skill))?.params)
        }

        @Test
        fun `a task begun inside an app gets a launch of that app`() {
            val skill = TaskArchive.build("搜蜜雪", done.drop(1), emptyList(), emptyMap()).getOrThrow()
            assertEquals(SkillStep.Launch(mt), skill.steps.first())
        }

        @Test
        fun `unreplayable actions, too few steps and a missing name are refused`() {
            assertTrue(TaskArchive.build("x", done, listOf("用了 long_press"), emptyMap()).isFailure)
            val tooShort = listOf(SkillStep.Launch(mt), SkillStep.Back)
            assertTrue(TaskArchive.build("x", tooShort, emptyList(), emptyMap()).isFailure)
            assertTrue(TaskArchive.build(" ", done, emptyList(), emptyMap()).isFailure)
        }

        @Test
        fun `params whose word never appears are ignored`() {
            val skill = TaskArchive.build("点外卖", done, emptyList(), mapOf("饮品" to "柠檬水")).getOrThrow()
            assertTrue(skill.params.isEmpty())
        }
    }

    @Nested
    @DisplayName("TaskJournal")
    inner class Journal {
        @TempDir
        lateinit var dir: File

        private fun newJournal(repo: SkillRepository) = TaskJournal(repo)

        @Test
        fun `a successful task is saved, replacing an older skill of the same name`() =
            runTest {
                val repo = FileSkillRepository(File(dir, "s.json"), Dispatchers.Unconfined)
                val j = newJournal(repo)
                j.begin("点蜜雪冰城外卖")
                j.recordAll(done)
                val first = j.end(true, null, mapOf("店名" to "蜜雪冰城"))
                assertNotNull(first.archived)
                // Repaired later: same request, different steps; the new version wins.
                j.begin("点蜜雪冰城外卖")
                j.recordAll(done + SkillStep.Tap(NodeSelector(text = "去结算", packageName = mt)))
                j.end(true, null, mapOf("店名" to "蜜雪冰城"))
                assertEquals(1, repo.skills.value.size)
                assertEquals(
                    SkillStep.Tap(NodeSelector(text = "去结算", packageName = mt)),
                    repo.skills.value
                        .single()
                        .steps
                        .last(),
                )
                assertTrue(j.isEmpty)
            }

        @Test
        fun `a failed or blocked task saves nothing and the journal starts clean`() =
            runTest {
                val repo = FileSkillRepository(File(dir, "s.json"), Dispatchers.Unconfined)
                val j = newJournal(repo)
                j.begin("x")
                j.recordAll(done)
                assertNull(j.end(false, null, emptyMap()).archived)
                j.begin("y")
                j.recordAll(done)
                j.cannotReplay("用了 vault_fill")
                val blocked = j.end(true, null, emptyMap())
                assertNull(blocked.archived)
                assertTrue(blocked.message.contains("vault_fill"))
                assertTrue(repo.skills.value.isEmpty())
                assertTrue(j.isEmpty)
            }
    }

    @Nested
    @DisplayName("SelectorFactory")
    inner class Selectors {
        @Test
        fun `a card is stored by its title's words and class, not its position`() {
            val card =
                Node(
                    id = "card",
                    clickable = true,
                    className = "android.view.ViewGroup",
                    top = 500,
                    height = 300,
                    children = listOf(Node(text = "蜜雪冰城(西电东区店)", className = "android.view.ViewGroup", top = 520)),
                )
            val echo = Node(text = "蜜雪冰城", top = 100)
            val found = SelectorFactory.forNode(listOf(window(mt, echo, card)), "card") as SelectorResult.Found
            val expected = NodeSelector("蜜雪冰城(西电东区店)", TextMatch.EXACT, null, "android.view.ViewGroup", mt)
            assertEquals(expected, found.selector)
        }

        @Test
        fun `a control with neither words nor id cannot be stored, nor can an ambiguous one`() {
            val blank = Node(id = "blank", clickable = true, top = 300)
            assertTrue(SelectorFactory.forNode(listOf(window(mt, blank)), "blank") is SelectorResult.Unusable)
            val first = Node(id = "a", text = "选规格", clickable = true, top = 600)
            val second = Node(id = "b", text = "选规格", clickable = true, top = 1000)
            assertTrue(SelectorFactory.forNode(listOf(window(mt, first, second)), "b") is SelectorResult.Unusable)
            assertTrue(SelectorFactory.forNode(listOf(window(mt, first, second)), "a") is SelectorResult.Found)
        }

        @Test
        fun `a tap position resolves to the control under it`() {
            val button = Node(text = "加入购物车", clickable = true, top = 2000, height = 100)
            val found = SelectorFactory.atPoint(listOf(window(mt, button)), 250f, 2050f) as SelectorResult.Found
            assertEquals("加入购物车", found.selector.text)
            assertTrue(SelectorFactory.atPoint(listOf(window(mt, button)), 250f, 10f) !is SelectorResult.Found)
        }
    }
}
