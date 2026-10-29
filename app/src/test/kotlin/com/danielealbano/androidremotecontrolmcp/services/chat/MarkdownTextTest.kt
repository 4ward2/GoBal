package com.danielealbano.androidremotecontrolmcp.services.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MarkdownText")
class MarkdownTextTest {
    @Test
    fun `bold, italic, code, strike and links lose their markers and keep their style`() {
        val runs = MarkdownText.runs("你最爱吃的是**小鱼干**，*别*贪嘴，`code`，~~旧~~，[官网](https://x.com)。")
        assertEquals(listOf("你最爱吃的是", "小鱼干", "，", "别", "贪嘴，", "code", "，", "旧", "，", "官网", "。"), runs.map { it.text })
        assertTrue(runs[1].bold)
        assertTrue(runs[3].italic)
        assertTrue(runs[5].code)
        assertTrue(runs[7].strike)
        assertEquals("https://x.com", runs[9].link)
        assertEquals("你最爱吃的是小鱼干，别贪嘴，code，旧，官网。", MarkdownText.plain(runs.joinToString("") { it.text }))
    }

    @Test
    fun `bullets, numbers, headings, quotes and rules are drawn without markdown syntax`() {
        val text = "# 体检\n---\n- 血压 128/82\n  * 血糖 5.6\n1. 散步\n2) 少盐\n> 医生说\n普通行"
        val plain = MarkdownText.plain(text)
        assertEquals("体检\n\n•  血压 128/82\n  •  血糖 5.6\n1. 散步\n2. 少盐\n│ 医生说\n普通行", plain)
        val heading = MarkdownText.runs(text).first()
        assertTrue(heading.heading && heading.bold)
    }

    @Test
    fun `a rule between two paragraphs leaves one empty line, and code keeps its own`() {
        assertEquals("a\n\nb", MarkdownText.plain("a\n\n---\n\nb"))
        assertEquals("a\n\nb", MarkdownText.plain("a\n\n\n\n\nb"))
        assertEquals("a\nx\n\n\n---\ny", MarkdownText.plain("a\n```\nx\n\n\n---\ny\n```"))
    }

    @Test
    fun `fenced code keeps its lines as code and drops the fences`() {
        val runs = MarkdownText.runs("说明：\n```\nadb shell\nls\n```\n完")
        assertEquals("说明：\nadb shell\nls\n完", runs.joinToString("") { it.text })
        assertTrue(runs.first { it.text == "adb shell" }.code)
        assertTrue(runs.first { it.text == "完" }.let { !it.code })
    }

    @Test
    fun `underscores inside words and lone asterisks are left alone`() {
        assertEquals("snake_case_name 和 2 * 3", MarkdownText.plain("snake_case_name 和 2 * 3"))
        assertEquals("a_b", MarkdownText.plain("a_b"))
    }

    @Test
    fun `plain text passes through unchanged`() {
        val text = "喵～今天天气不错。\n要出去走走吗？"
        assertEquals(text, MarkdownText.plain(text))
    }
}
