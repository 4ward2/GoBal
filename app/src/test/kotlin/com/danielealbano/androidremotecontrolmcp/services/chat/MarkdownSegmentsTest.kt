package com.danielealbano.androidremotecontrolmcp.services.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MarkdownText.segments")
class MarkdownSegmentsTest {
    @Test
    fun `text without a fence is one segment`() {
        assertEquals(listOf(Segment("**好**的\n第二行")), MarkdownText.segments("**好**的\n第二行"))
    }

    @Test
    fun `a fenced block becomes its own segment with its language`() {
        val text = "先看代码：\n```kotlin\nval a = 1\n  val b = 2\n```\n就这样。"
        assertEquals(
            listOf(
                Segment("先看代码："),
                Segment("val a = 1\n  val b = 2", code = true, language = "kotlin"),
                Segment("就这样。"),
            ),
            MarkdownText.segments(text),
        )
    }

    @Test
    fun `a fence without a language and two blocks in a row`() {
        val text = "```\na\n```\n```c++\nb\n```"
        assertEquals(
            listOf(Segment("a", code = true), Segment("b", code = true, language = "c++")),
            MarkdownText.segments(text),
        )
    }

    @Test
    fun `a fence still open while the reply is being written runs to the end`() {
        assertEquals(
            listOf(Segment("写："), Segment("print(1)", code = true, language = "python")),
            MarkdownText.segments("写：\n```python\nprint(1)"),
        )
    }

    @Test
    fun `blank lines around a block do not become empty segments`() {
        val text = "上面\n\n```\nx\n```\n\n"
        assertEquals(listOf(Segment("上面"), Segment("x", code = true)), MarkdownText.segments(text))
    }

    @Test
    fun `an empty code block is kept so its box still shows`() {
        assertEquals(listOf(Segment("", code = true, language = "sh")), MarkdownText.segments("```sh\n```"))
    }
}
