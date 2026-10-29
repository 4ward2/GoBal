package com.danielealbano.androidremotecontrolmcp.services.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Search tree")
class SearchTreeTest {
    private val list = "拼好饭 > 汉堡披萨"

    private fun stage(tree: TaskTree) = SearchProgress.stage(tree.views())

    @Test
    fun `a lookup goes from finding its list to seeing it whole, opening what was marked, and answering`() {
        val tree = TaskTree()
        assertEquals("", SearchProgress.render(tree.views()))
        tree.arrive("拼好饭", 1)
        assertEquals(SearchProgress.Stage.SCOPE, stage(tree))
        tree.arrive(list, 2)
        assertTrue(tree.write("$list > 牛肉堡", "¥1.9", item = true))
        assertEquals(SearchProgress.Stage.SURVEY, stage(tree))
        assertTrue(tree.settle(list, reason = null))
        assertFalse(tree.settle(list, reason = null))
        assertEquals(SearchProgress.Stage.ANSWER, stage(tree))
        tree.plan(list, listOf("牛肉堡", "鸡腿堡"))
        assertEquals(SearchProgress.Stage.DIVE, stage(tree))
        assertEquals(listOf("拼好饭 > 汉堡披萨 > 鸡腿堡 (not opened)"), SearchProgress.unsettled(tree.views()))
        tree.arrive("$list > 鸡腿堡", 3)
        assertEquals(listOf("拼好饭 > 汉堡披萨 > 鸡腿堡 (opened, nothing noted)"), SearchProgress.unsettled(tree.views()))
        tree.write("$list > 鸡腿堡", "套餐 ¥3.9 / ¥7.9")
        assertEquals(SearchProgress.Stage.ANSWER, stage(tree))
        assertEquals("", SearchProgress.leftOpen(tree.views()))
    }

    @Test
    fun `the progress names what is left in the stage the task is in, and only there`() {
        val tree = TaskTree()
        tree.arrive(list, 2)
        tree.write("$list > 牛肉堡", "¥1.9", item = true)
        tree.write("$list > 鸡腿堡", "¥3.9", item = true)
        tree.plan(list, listOf("牛肉堡"))
        val shown = SearchProgress.render(tree.views())
        assertTrue(shown.contains("[done] 1. Find the list(s)"))
        assertTrue(
            shown.contains(
                "[now] 2. See each list to its end (scroll on) or close it with why the rest " +
                    "cannot matter — 拼好饭 > 汉堡披萨: 2 items noted, end not seen",
            ),
        )
        assertTrue(
            shown.contains(
                "[todo] 3. Open what is marked and note what is inside, or close what " +
                    "is not worth it\n",
            ),
        )
        assertTrue(shown.endsWith("[todo] 4. Answer from the map with done"))
    }

    @Test
    fun `many places waiting are named up to a limit`() {
        val tree = TaskTree()
        tree.arrive(list, 2)
        tree.settle(list, "只看这一屏")
        tree.plan(list, (1..10).map { "第${it}样" })
        val shown = SearchProgress.render(tree.views())
        assertTrue(shown.contains("— waiting: 拼好饭 > 汉堡披萨 > 第1样; "))
        assertTrue(shown.contains("拼好饭 > 汉堡披萨 > 第8样; and 2 more"))
    }

    @Test
    fun `the drawing puts what was noted under its place and says how each place stands`() {
        val tree = TaskTree()
        tree.arrive(list, 2)
        tree.write(list, "按价格排过序")
        tree.write("$list > 牛肉堡", "¥1.9\n新人价", item = true)
        tree.plan(list, listOf("鸡腿堡", "沙拉"))
        tree.settle("$list > 沙拉", "比牛肉堡贵很多，不用看了，因为价格高出好几倍而且不在要求的范围里")
        assertEquals(
            "- 拼好饭\n" +
                "  - 汉堡披萨 [here, list, end not seen]\n" +
                "      · 按价格排过序\n" +
                "    - 牛肉堡 [from the list]\n" +
                "        · ¥1.9 新人价\n" +
                "    - 鸡腿堡 [to open]\n" +
                "    - 沙拉 [closed: 比牛肉堡贵很多，不用看了，因为价格高出好几倍而且不在要求的范围里]",
            TreeDrawing.draw(tree.views()),
        )
        tree.settle(list, "够了")
        assertTrue(TreeDrawing.draw(tree.views()).contains("汉堡披萨 [here, list, closed: 够了]"))
    }

    @Test
    fun `a name is found as a path, under the place the phone is at, or as the only place of that name`() {
        val tree = TaskTree()
        tree.arrive(list, 2)
        tree.write("$list > 牛肉堡", "¥1.9", item = true)
        tree.write("拼好饭 > 饮品 > 冰粉", "¥1.9", item = true)
        tree.write("拼好饭 > 甜点 > 冰粉", "¥2.9", item = true)
        assertEquals("$list > 牛肉堡", tree.find("牛肉堡"))
        assertEquals("$list > 牛肉堡", tree.find("拼好饭>汉堡披萨>牛肉堡"))
        assertEquals("拼好饭 > 饮品", tree.find("饮品"))
        assertNull(tree.find("冰粉"))
        assertNull(tree.find("火锅"))
        assertNull(tree.find(" > "))
    }

    @Test
    fun `notes have a size limit, and nothing empty is kept`() {
        val tree = TaskTree()
        assertFalse(tree.write("", "¥1.9"))
        assertFalse(tree.write(list, "  "))
        repeat(6) { assertTrue(tree.write("$list > 第${it}样", "x".repeat(1_300), item = true)) }
        assertFalse(tree.write(list, "x".repeat(1_000)))
        assertTrue(
            tree
                .views()
                .first { it.path == "$list > 第0样" }
                .facts
                .single()
                .length == 1_200,
        )
    }

    @Test
    fun `the way to a place is forgotten when the app starts over, but what was noted there stays`() {
        val tree = TaskTree()
        tree.arrive(list, 2)
        tree.write(list, "按价格排过序")
        assertEquals(2, tree.stepsTo(list))
        tree.forgetWays()
        assertNull(tree.stepsTo(list))
        assertEquals(listOf("按价格排过序"), tree.views().first { it.path == list }.facts)
        tree.arrive(list, 5)
        assertEquals(5, tree.stepsTo(list))
    }

    @Test
    fun `a place only passed through owes nothing, and what is left open is told to the user by name`() {
        val tree = TaskTree()
        tree.arrive("美团首页 > 频道", 1)
        assertEquals(emptyList<String>(), SearchProgress.unsettled(tree.views()))
        tree.plan(list, listOf("牛肉堡"))
        // The list the item was marked under has not been opened either.
        assertEquals("\n\n（没看完的：汉堡披萨、牛肉堡）", SearchProgress.leftOpen(tree.views()))
    }

    @Test
    fun `notes about items are read both as an object and as a list of named entries`() {
        val asObject = AgentProtocol.parse("""{"action":"note","args":{"items":{"冰粉":"¥1.9"},"about":"饮品"}}""")
        assertEquals(AgentMove.Note("", "饮品", mapOf("冰粉" to "¥1.9")), asObject)
        val asList =
            AgentProtocol.parse(
                """{"action":"note","args":{"items":[{"name":"冰粉","text":"¥1.9"},{"name":"奶茶","fact":"¥3.9"},""" +
                    """{"text":"无名"}]}}""",
            )
        assertEquals(AgentMove.Note("", "", mapOf("冰粉" to "¥1.9", "奶茶" to "¥3.9")), asList)
        assertEquals(
            AgentMove.Close("饮品", "看完了"),
            AgentProtocol.parse("""{"action":"close","args":{"at":"饮品","reason":"看完了"}}"""),
        )
    }
}
