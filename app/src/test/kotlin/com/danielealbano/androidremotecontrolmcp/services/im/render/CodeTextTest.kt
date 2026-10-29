package com.danielealbano.androidremotecontrolmcp.services.im.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CodeText")
class CodeTextTest {
    @Test
    fun `a short block of narrow lines is not long`() {
        val block = MarkdownBlock.Code("bash", listOf("ls", "pwd"), closed = true)
        assertFalse(CodeText.isLong(block))
    }

    @Test
    fun `more than the line count threshold makes a block long`() {
        val block = MarkdownBlock.Code("", (1..(CodeText.LONG_LINE_COUNT + 1)).map { "x" }, closed = true)
        assertTrue(CodeText.isLong(block))
    }

    @Test
    fun `exactly the line count threshold is not long by itself`() {
        val block = MarkdownBlock.Code("", (1..CodeText.LONG_LINE_COUNT).map { "x" }, closed = true)
        assertFalse(CodeText.isLong(block))
    }

    @Test
    fun `a single line wider than the width threshold makes a block long`() {
        val wide = "x".repeat(CodeText.LONG_LINE_WIDTH + 1)
        val block = MarkdownBlock.Code("", listOf(wide), closed = true)
        assertTrue(CodeText.isLong(block))
    }

    @Test
    fun `cjk characters count double width when measuring a line`() {
        val block = MarkdownBlock.Code("", listOf("中".repeat(CodeText.LONG_LINE_WIDTH / 2 + 1)), closed = true)
        assertTrue(CodeText.isLong(block))
    }

    @Test
    fun `plain text includes a language header when a language is set`() {
        val block = MarkdownBlock.Code("kotlin", listOf("val a = 1", "val b = 2"), closed = true)
        assertEquals("[代码：kotlin]\nval a = 1\nval b = 2", CodeText.plain(block))
    }

    @Test
    fun `plain text has no header when there is no language`() {
        val block = MarkdownBlock.Code("", listOf("ls"), closed = true)
        assertEquals("ls", CodeText.plain(block))
    }

    @Test
    fun `source rebuilds the fence and closes it only when the block was closed`() {
        val closed = MarkdownBlock.Code("kotlin", listOf("val a = 1"), closed = true)
        assertEquals("```kotlin\nval a = 1\n```", CodeText.source(closed))
        val open = MarkdownBlock.Code("kotlin", listOf("val a = 1"), closed = false)
        assertEquals("```kotlin\nval a = 1", CodeText.source(open))
    }
}
