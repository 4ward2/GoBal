package com.danielealbano.androidremotecontrolmcp.services.im.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("InlineMarkdown")
class InlineMarkdownTest {
    @Test
    fun `bold, italic, strike and inline code lose their markers but keep their text`() {
        assertEquals("小鱼干", InlineMarkdown.strip("**小鱼干**"))
        assertEquals("小鱼干", InlineMarkdown.strip("__小鱼干__"))
        assertEquals("别", InlineMarkdown.strip("*别*"))
        assertEquals("旧", InlineMarkdown.strip("~~旧~~"))
        assertEquals("a*b_c", InlineMarkdown.strip("`a*b_c`"))
    }

    @Test
    fun `a link becomes text followed by the url in parentheses`() {
        assertEquals("官网（https://x.com）", InlineMarkdown.strip("[官网](https://x.com)"))
    }

    @Test
    fun `a link whose text equals its url keeps only the url`() {
        assertEquals("https://x.com", InlineMarkdown.strip("[https://x.com](https://x.com)"))
    }

    @Test
    fun `an image becomes a bracketed caption followed by the url`() {
        assertEquals("[图片：截图]（https://x.com/a.png）", InlineMarkdown.strip("![截图](https://x.com/a.png)"))
    }

    @Test
    fun `an image without a caption still carries the url`() {
        assertEquals("[图片：]（https://x.com/a.png）", InlineMarkdown.strip("![](https://x.com/a.png)"))
    }

    @Test
    fun `loose html tags are removed but their inner text stays`() {
        assertEquals("粗体文字", InlineMarkdown.strip("<b>粗体文字</b>"))
    }

    @Test
    fun `snake case identifiers and a lone multiplication asterisk are left alone`() {
        assertEquals("snake_case_name 和 2 * 3", InlineMarkdown.strip("snake_case_name 和 2 * 3"))
        assertEquals("a_b", InlineMarkdown.strip("a_b"))
    }

    @Test
    fun `a file glob is not mistaken for an italic marker`() {
        assertEquals("改了 *.kt 和 *.md 文件", InlineMarkdown.strip("改了 *.kt 和 *.md 文件"))
    }

    @Test
    fun `plain text with no markers passes through unchanged`() {
        val text = "喵～今天天气不错，要出去走走吗？"
        assertEquals(text, InlineMarkdown.strip(text))
    }

    @Test
    fun `an emoji next to a marker is preserved`() {
        assertEquals("完成了 🎉", InlineMarkdown.strip("**完成了** 🎉"))
    }
}
