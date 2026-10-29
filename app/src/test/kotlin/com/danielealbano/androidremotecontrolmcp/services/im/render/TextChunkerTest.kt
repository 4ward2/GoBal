package com.danielealbano.androidremotecontrolmcp.services.im.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TextChunker")
class TextChunkerTest {
    @Test
    fun `empty text produces no pieces`() {
        assertEquals(emptyList<String>(), TextChunker.chunk("", 10))
    }

    @Test
    fun `text that already fits comes back as a single piece with no numbering`() {
        assertEquals(listOf("hello"), TextChunker.chunk("hello", 10))
    }

    @Test
    fun `a paragraph break is preferred over cutting mid word`() {
        val text = "A".repeat(20) + "\n\n" + "B".repeat(20)
        val pieces = TextChunker.chunk(text, 40)
        assertEquals(listOf("${"A".repeat(20)}\n\n（1/2）", "${"B".repeat(20)}（2/2）"), pieces)
    }

    @Test
    fun `with no boundary at all the text is cut exactly at the budget`() {
        val text = "A".repeat(20) + "B".repeat(20)
        val pieces = TextChunker.chunk(text, 25)
        assertEquals(listOf("${"A".repeat(20)}（1/2）", "${"B".repeat(20)}（2/2）"), pieces)
    }

    @Test
    fun `a sentence ending is preferred over a hard cut`() {
        val text = "A".repeat(20) + "。" + "B".repeat(20)
        val pieces = TextChunker.chunk(text, 40)
        assertEquals(listOf("${"A".repeat(20)}。（1/2）", "${"B".repeat(20)}（2/2）"), pieces)
    }

    @Test
    fun `a bare line break is used as a boundary when there is no sentence punctuation`() {
        val text = "A".repeat(20) + "\n" + "B".repeat(20)
        val pieces = TextChunker.chunk(text, 40)
        assertEquals(listOf("${"A".repeat(20)}\n（1/2）", "${"B".repeat(20)}（2/2）"), pieces)
    }

    @Test
    fun `whitespace is used as a boundary when there is nothing better`() {
        val text = "A".repeat(20) + " " + "B".repeat(20)
        val pieces = TextChunker.chunk(text, 40)
        assertEquals(listOf("${"A".repeat(20)} （1/2）", "${"B".repeat(20)}（2/2）"), pieces)
    }

    @Test
    fun `numbering only appears once a reply had to be split into more than one piece`() {
        val single = TextChunker.chunk("short reply", 200)
        assertFalse(single.single().contains("（"))
    }

    @Test
    fun `every piece, including its numbering suffix, stays within the limit`() {
        // Kept short enough that even the smallest maxChars below only ever needs a one- or
        // two-digit piece count, so the "（i/N）" suffix itself always fits the budget. A much
        // longer reply cut to a tiny maxChars can need a three-digit count whose own suffix is
        // wider than maxChars — see the documented limitation on the class below.
        val text = "中文混排 mixed text, no obvious break points at all, over and over. "
        for (maxChars in listOf(10, 16, 30, 50, 77)) {
            val pieces = TextChunker.chunk(text, maxChars)
            pieces.forEach { assertTrue(it.length <= maxChars, "piece $it exceeds $maxChars") }
        }
    }

    @Test
    fun `a limit too small for even its own numbering suffix may be exceeded, never crash`() {
        // When maxChars is smaller than "（<count>/<count>）" needs, no numbering scheme can keep
        // every piece within it; splitting still terminates and loses no content.
        val text = "中文混排 mixed text, no obvious break points at all, over and over. ".repeat(6)
        val pieces = TextChunker.chunk(text, 5)
        assertTrue(pieces.isNotEmpty())
        assertEquals(text, pieces.joinToString("") { it.replace(Regex("（\\d+/\\d+）$"), "") })
    }

    @Test
    fun `splitting never drops or duplicates characters`() {
        val suffix = Regex("（\\d+/\\d+）$")
        val samples =
            listOf(
                "A".repeat(20) + "\n\n" + "B".repeat(20) to 40,
                "A".repeat(20) + "。" + "B".repeat(20) to 40,
                "A".repeat(50) to 7,
                "😀".repeat(10) to 3,
                "混合 text with 一些 punctuation. 还有更多内容在这里继续写下去。" to 9,
            )
        samples.forEach { (text, maxChars) ->
            val pieces = TextChunker.chunk(text, maxChars)
            val reassembled = pieces.joinToString("") { it.replace(suffix, "") }
            assertEquals(text, reassembled, "lost or duplicated characters for maxChars=$maxChars")
        }
    }

    @Test
    fun `a hard cut never splits an emoji surrogate pair across two pieces`() {
        val text = "😀".repeat(30)
        val pieces = TextChunker.chunk(text, 3)
        assertTrue(pieces.size > 1)
        pieces.forEach { assertFalse(hasUnpairedSurrogate(it), "piece $it has an unpaired surrogate") }
    }

    @Test
    fun `an unreasonably small limit still makes progress instead of looping forever`() {
        val pieces = TextChunker.chunk("😀😀😀", 1)
        assertTrue(pieces.isNotEmpty())
        assertEquals("😀😀😀", pieces.joinToString("") { it.replace(Regex("（\\d+/\\d+）$"), "") })
    }

    private fun hasUnpairedSurrogate(text: String): Boolean {
        var i = 0
        var unpaired = false
        while (i < text.length && !unpaired) {
            val ch = text[i]
            when {
                ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> i += 2
                ch.isHighSurrogate() || ch.isLowSurrogate() -> unpaired = true
                else -> i++
            }
        }
        return unpaired
    }
}
