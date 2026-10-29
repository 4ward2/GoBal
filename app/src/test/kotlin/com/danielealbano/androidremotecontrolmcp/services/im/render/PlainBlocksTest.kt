package com.danielealbano.androidremotecontrolmcp.services.im.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PlainBlocks")
class PlainBlocksTest {
    @Test
    fun `a heading loses its markers and stands alone with a blank line around it`() {
        val text = PlainBlocks.render(listOf("before", "# 体检", "after"))
        assertEquals("before\n\n体检\n\nafter", text)
    }

    @Test
    fun `a three level nested unordered list keeps indentation and gets a marker per level`() {
        val text =
            PlainBlocks.render(
                listOf(
                    "- 后端",
                    "  - 鉴权",
                    "    - 子项",
                ),
            )
        assertEquals("• 后端\n  ◦ 鉴权\n    ▪ 子项", text)
    }

    @Test
    fun `an ordered list keeps its numbers and either delimiter style renders the same way`() {
        val text = PlainBlocks.render(listOf("1. first", "2) second"))
        assertEquals("1. first\n2. second", text)
    }

    @Test
    fun `task list items become checkbox glyphs, case insensitively`() {
        val text = PlainBlocks.render(listOf("- [ ] todo", "- [x] done", "- [X] also done"))
        assertEquals("☐ todo\n☑ done\n☑ also done", text)
    }

    @Test
    fun `a blockquote is prefixed and nesting adds another bar`() {
        val text = PlainBlocks.render(listOf("> 医生说", "> > 再深一层"))
        assertEquals("｜ 医生说\n｜ ｜ 再深一层", text)
    }

    @Test
    fun `thematic breaks of dashes, stars or underscores are removed`() {
        val text = PlainBlocks.render(listOf("before", "---", "***", "___", "after"))
        assertEquals("before\nafter", text)
    }

    @Test
    fun `a br tag becomes a real line break`() {
        val text = PlainBlocks.render(listOf("line one<br>line two"))
        assertEquals("line one\nline two", text)
    }

    @Test
    fun `inline markers inside list and quote content are stripped too`() {
        val text = PlainBlocks.render(listOf("- **重要** 事项", "> *轻声* 说"))
        assertEquals("• 重要 事项\n｜ 轻声 说", text)
    }

    @Test
    fun `plain paragraphs with no special syntax pass through unchanged`() {
        val text = PlainBlocks.render(listOf("普通第一行", "普通第二行"))
        assertEquals("普通第一行\n普通第二行", text)
    }
}
