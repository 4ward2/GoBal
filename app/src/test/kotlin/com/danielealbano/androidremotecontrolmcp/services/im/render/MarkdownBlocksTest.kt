package com.danielealbano.androidremotecontrolmcp.services.im.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MarkdownBlocks")
class MarkdownBlocksTest {
    @Test
    fun `plain paragraphs become a single Lines block`() {
        val blocks = MarkdownBlocks.parse("first line\nsecond line")
        assertEquals(listOf(MarkdownBlock.Lines(listOf("first line", "second line"))), blocks)
    }

    @Test
    fun `empty input produces a single empty Lines block`() {
        // Kotlin's String.split("\n") on "" returns listOf(""), not an empty list, so parse("")
        // is one empty line rather than no blocks at all. ChannelRenderer.render short-circuits on
        // an empty reply before ever calling parse, so this quirk never reaches a real caller.
        assertEquals(listOf(MarkdownBlock.Lines(listOf(""))), MarkdownBlocks.parse(""))
    }

    @Test
    fun `a closed fence becomes a Code block and does not leak into surrounding text`() {
        val blocks = MarkdownBlocks.parse("before\n```kotlin\nval a = 1\n```\nafter")
        assertEquals(
            listOf(
                MarkdownBlock.Lines(listOf("before")),
                MarkdownBlock.Code("kotlin", listOf("val a = 1"), closed = true),
                MarkdownBlock.Lines(listOf("after")),
            ),
            blocks,
        )
    }

    @Test
    fun `an unclosed fence runs to the end of the text instead of throwing`() {
        val blocks = MarkdownBlocks.parse("before\n```bash\nls\npwd")
        assertEquals(
            listOf(
                MarkdownBlock.Lines(listOf("before")),
                MarkdownBlock.Code("bash", listOf("ls", "pwd"), closed = false),
            ),
            blocks,
        )
    }

    @Test
    fun `a pipe table with a separator row becomes a Table block`() {
        val text = "intro\n| a | b |\n|---|---|\n| 1 | 2 |\nafter"
        val blocks = MarkdownBlocks.parse(text)
        assertEquals(
            listOf(
                MarkdownBlock.Lines(listOf("intro")),
                MarkdownBlock.Table(listOf("| a | b |", "|---|---|", "| 1 | 2 |")),
                MarkdownBlock.Lines(listOf("after")),
            ),
            blocks,
        )
    }

    @Test
    fun `a dollar block formula is captured with its delimiters`() {
        val blocks = MarkdownBlocks.parse("before\n$$\nE = mc^2\n$$\nafter")
        assertEquals(
            listOf(
                MarkdownBlock.Lines(listOf("before")),
                MarkdownBlock.Formula(listOf("$$", "E = mc^2", "$$")),
                MarkdownBlock.Lines(listOf("after")),
            ),
            blocks,
        )
    }

    @Test
    fun `a single-line bracket formula is its own block`() {
        val blocks = MarkdownBlocks.parse("before\n\\[ x < 1 \\]\nafter")
        assertEquals(
            listOf(
                MarkdownBlock.Lines(listOf("before")),
                MarkdownBlock.Formula(listOf("\\[ x < 1 \\]")),
                MarkdownBlock.Lines(listOf("after")),
            ),
            blocks,
        )
    }

    @Test
    fun `a pipe or dollar sign inside a fenced code block is not read as a table or formula`() {
        val blocks = MarkdownBlocks.parse("```\n| not a table |\n$$ not a formula $$\n```")
        assertTrue(blocks.single() is MarkdownBlock.Code)
        val code = blocks.single() as MarkdownBlock.Code
        assertEquals(listOf("| not a table |", "$$ not a formula $$"), code.lines)
    }

    @Test
    fun `a lone pipe line without a separator row stays ordinary text`() {
        val blocks = MarkdownBlocks.parse("just | a pipe | here")
        assertEquals(1, blocks.size)
        assertFalse(blocks.first() is MarkdownBlock.Table)
    }
}
