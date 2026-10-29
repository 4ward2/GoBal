package com.danielealbano.androidremotecontrolmcp.services.im.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ChannelRenderer")
class ChannelRendererTest {
    private val markdownTraits = ChannelTraits(markdown = true, images = true, files = true, maxChars = 4000)
    private val plainNoImages = ChannelTraits(markdown = false, images = false, files = false, maxChars = 4000)
    private val plainWithImages = ChannelTraits(markdown = false, images = true, files = false, maxChars = 4000)

    @Test
    fun `empty markdown renders to no parts at all`() {
        assertEquals(emptyList<OutboundPart>(), ChannelRenderer.render("", plainNoImages))
        assertEquals(emptyList<OutboundPart>(), ChannelRenderer.render("", markdownTraits))
    }

    @Test
    fun `a markdown channel gets the reply unchanged, only split by length`() {
        val text = "**保留原样** 的 markdown，连表格都不用管：\n| a | b |\n|---|---|\n| 1 | 2 |"
        val parts = ChannelRenderer.render(text, markdownTraits)
        assertEquals(listOf(OutboundPart.Text(text)), parts)
    }

    @Test
    fun `a long table becomes a picture when the channel can carry pictures`() {
        val text = "intro\n| a | b |\n|---|---|\n| 1 | 2 |\nafter"
        val parts = ChannelRenderer.render(text, plainWithImages)
        val picture = parts.filterIsInstance<OutboundPart.Picture>().single()
        assertEquals(PictureKind.TABLE, picture.kind)
        assertTrue(picture.source.contains("| 1 | 2 |"))
        assertFalse(picture.fallback.contains('|'))
        assertEquals(listOf(OutboundPart.Text("intro"), picture, OutboundPart.Text("after")), parts)
    }

    @Test
    fun `a table becomes plain text rows when the channel cannot carry pictures`() {
        val text = "| a | b |\n|---|---|\n| 1 | 2 |"
        val parts = ChannelRenderer.render(text, plainNoImages)
        assertEquals(listOf(OutboundPart.Text("（表格，共 1 行）\n\na：1\nb：2")), parts)
    }

    @Test
    fun `a short code block is sent as plain text, never a picture`() {
        val text = "before\n```\nls\n```\nafter"
        val parts = ChannelRenderer.render(text, plainWithImages)
        assertEquals(listOf(OutboundPart.Text("before\n\nls\n\nafter")), parts)
    }

    @Test
    fun `a long code block becomes a picture with a text fallback when images are allowed`() {
        val longBody = (1..12).joinToString("\n") { "line $it" }
        val text = "before\n```kotlin\n$longBody\n```\nafter"
        val parts = ChannelRenderer.render(text, plainWithImages)
        val picture = parts.filterIsInstance<OutboundPart.Picture>().single()
        assertEquals(PictureKind.CODE, picture.kind)
        assertTrue(picture.source.startsWith("```kotlin"))
        assertTrue(picture.fallback.startsWith("[代码：kotlin]"))
    }

    @Test
    fun `a long code block becomes plain text with a language header when images are not allowed`() {
        val longBody = (1..12).joinToString("\n") { "line $it" }
        val text = "```kotlin\n$longBody\n```"
        val parts = ChannelRenderer.render(text, plainNoImages)
        val only = parts.single() as OutboundPart.Text
        assertTrue(only.text.startsWith("[代码：kotlin]\nline 1"))
    }

    @Test
    fun `a block formula becomes a picture with its delimiters stripped in the fallback`() {
        val text = "$$\nE = mc^2\n$$"
        val parts = ChannelRenderer.render(text, plainWithImages)
        val picture = parts.single() as OutboundPart.Picture
        assertEquals(PictureKind.FORMULA, picture.kind)
        assertEquals("$$\nE = mc^2\n$$", picture.source)
        assertEquals("E = mc^2", picture.fallback)
    }

    @Test
    fun `text around a picture is split into separate parts, never silently merged across it`() {
        val text = "before text\n\n| a |\n|---|\n| 1 |\n\nafter text"
        val parts = ChannelRenderer.render(text, plainWithImages)
        assertEquals(3, parts.size)
        assertTrue(parts[0] is OutboundPart.Text)
        assertTrue(parts[1] is OutboundPart.Picture)
        assertTrue(parts[2] is OutboundPart.Text)
    }

    @Test
    fun `adjacent plain paragraphs are merged into one message instead of one part per paragraph`() {
        val text = "第一段。\n\n第二段。\n\n第三段。"
        val parts = ChannelRenderer.render(text, plainNoImages)
        assertEquals(listOf(OutboundPart.Text("第一段。\n\n第二段。\n\n第三段。")), parts)
    }

    @Test
    fun `heading blank lines around a short reply do not leave stray blank lines at the edges`() {
        val text = "# 标题\n正文"
        val parts = ChannelRenderer.render(text, plainNoImages)
        assertEquals(listOf(OutboundPart.Text("标题\n\n正文")), parts)
    }

    @Test
    fun `downgrade splits a picture fallback to fit the limit and numbers the pieces`() {
        val fallback = "A".repeat(20) + "\n\n" + "B".repeat(20)
        val picture = OutboundPart.Picture(PictureKind.TABLE, "source", fallback)
        val texts = ChannelRenderer.downgrade(picture, 40)
        val firstPiece = OutboundPart.Text("${"A".repeat(20)}\n\n（1/2）")
        val secondPiece = OutboundPart.Text("${"B".repeat(20)}（2/2）")
        assertEquals(listOf(firstPiece, secondPiece), texts)
    }

    @Test
    fun `a reply with no markdown at all comes back as plain text unchanged`() {
        val text = "普通的一句话，没有任何标记。"
        assertEquals(listOf(OutboundPart.Text(text)), ChannelRenderer.render(text, plainNoImages))
    }
}
