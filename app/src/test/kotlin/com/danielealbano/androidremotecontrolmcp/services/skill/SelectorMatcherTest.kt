package com.danielealbano.androidremotecontrolmcp.services.skill

import com.danielealbano.androidremotecontrolmcp.services.skill.SkillTestTrees.Node
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillTestTrees.window
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("SelectorMatcher")
class SelectorMatcherTest {
    private val app = "com.sankuai.meituan"

    @Test
    fun `a label inside a card resolves to the card as click target`() {
        val card =
            Node(
                id = "card",
                clickable = true,
                className = "android.view.ViewGroup",
                children = listOf(Node(id = "title", text = "蜜雪冰城(高新店)"), Node(text = "4.8分")),
            )
        val contains = NodeSelector(text = "蜜雪冰城", match = TextMatch.CONTAINS)
        val hit = SelectorMatcher.find(listOf(window(app, card)), contains, HitPurpose.TAP)
        assertEquals("title", hit?.node?.id)
        assertEquals("card", hit?.clickTarget?.id)
    }

    @Test
    fun `content descriptions count as labels`() {
        val card = Node(id = "card", desc = "蜜雪冰城 月售9999", clickable = true)
        val contains = NodeSelector(text = "蜜雪冰城", match = TextMatch.CONTAINS)
        val hit = SelectorMatcher.find(listOf(window(app, card)), contains, HitPurpose.TAP)
        assertEquals("card", hit?.clickTarget?.id)
    }

    @Test
    fun `among containing labels the first shop on screen wins, not the shortest name`() {
        val header = Node(id = "echo", text = "蜜雪冰城", className = "android.widget.TextView", top = 146)
        val first = Node(id = "first", text = "蜜雪冰城(西安电子科技大学南校区东区店)", className = "android.view.ViewGroup", top = 1699)
        val second = Node(id = "second", text = "蜜雪冰城（西北大学南门店）", className = "android.view.ViewGroup", top = 2529)
        val hit =
            SelectorMatcher.find(
                listOf(window(app, header, second, first)),
                NodeSelector(text = "蜜雪冰城", match = TextMatch.CONTAINS, className = "ViewGroup"),
                HitPurpose.TAP,
            )
        assertEquals("first", hit?.node?.id)
    }

    @Test
    fun `exact match wins over a longer containing label`() {
        val long = Node(id = "long", text = "蜜雪冰城 新品", clickable = true)
        val exact = Node(id = "exact", text = "蜜雪冰城", clickable = true, top = 900)
        val hit =
            SelectorMatcher.find(
                listOf(window(app, long, exact)),
                NodeSelector(text = "蜜雪冰城", match = TextMatch.CONTAINS),
                HitPurpose.TAP,
            )
        assertEquals("exact", hit?.node?.id)
    }

    @Test
    fun `an input field holding the text is not what a tap means`() {
        val box = Node(id = "box", text = "蜜雪冰城", editable = true, className = "android.widget.EditText")
        val tapSel = NodeSelector(text = "蜜雪冰城", match = TextMatch.CONTAINS)
        assertNull(SelectorMatcher.find(listOf(window(app, box)), tapSel, HitPurpose.TAP))
        val byClass = tapSel.copy(className = "EditText")
        assertEquals("box", SelectorMatcher.find(listOf(window(app, box)), byClass, HitPurpose.TAP)?.node?.id)
    }

    @Test
    fun `exact matching ignores case and extra spaces but not other words`() {
        val n = Node(text = "  Go   To Cart ")
        assertTrue(
            SelectorMatcher.find(listOf(window(app, n)), NodeSelector(text = "go to cart"), HitPurpose.ANY) != null,
        )
        assertNull(SelectorMatcher.find(listOf(window(app, n)), NodeSelector(text = "go"), HitPurpose.ANY))
    }

    @Test
    fun `package, keyboard windows, invisible nodes and resource ids are respected`() {
        val hidden = Node(text = "搜索", visible = false)
        val other = window("com.other", Node(text = "搜索"))
        val keyboard = window("ime", Node(text = "搜索"), type = "INPUT_METHOD")
        val sel = NodeSelector(text = "搜索", packageName = app)
        assertNull(SelectorMatcher.find(listOf(window(app, hidden), other, keyboard), sel, HitPurpose.ANY))
        val byId = Node(id = "s", resourceId = "$app:id/search")
        val bySel = NodeSelector(resourceId = "$app:id/search")
        assertEquals("s", SelectorMatcher.find(listOf(window(app, byId)), bySel, HitPurpose.ANY)?.node?.id)
    }

    @Test
    fun `an empty selector matches nothing`() {
        assertNull(SelectorMatcher.find(listOf(window(app, Node(text = "x"))), NodeSelector(), HitPurpose.ANY))
    }

    @Test
    fun `screenShows ignores whitespace and keyboard windows`() {
        val windows = listOf(window(app, Node(text = "蜜雪 冰城")), window("ime", Node(text = "瑞幸"), type = "INPUT_METHOD"))
        assertTrue(SelectorMatcher.screenShows(windows, "蜜雪冰城"))
        assertFalse(SelectorMatcher.screenShows(windows, "瑞幸"))
        assertFalse(SelectorMatcher.screenShows(windows, " "))
    }

    @Test
    fun `firstInput finds the first visible editable field of the app`() {
        val windows = listOf(window(app, Node(text = "搜索"), Node(id = "in", editable = true)))
        assertEquals("in", SelectorMatcher.firstInput(windows, app)?.node?.id)
        assertNull(SelectorMatcher.firstInput(windows, "com.other"))
    }
}
