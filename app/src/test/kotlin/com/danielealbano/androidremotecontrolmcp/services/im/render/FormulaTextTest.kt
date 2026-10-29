package com.danielealbano.androidremotecontrolmcp.services.im.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("FormulaText")
class FormulaTextTest {
    @Test
    fun `a single-line dollar formula loses only its delimiters`() {
        assertEquals("E = mc^2", FormulaText.fallback(listOf("\$\$E = mc^2\$\$")))
    }

    @Test
    fun `a single-line bracket formula loses only its delimiters`() {
        assertEquals("x < 1", FormulaText.fallback(listOf("\\[ x < 1 \\]")))
    }

    @Test
    fun `a multi-line dollar formula keeps its inner content verbatim`() {
        val fallback = FormulaText.fallback(listOf("$$", "a_b * c", "$$"))
        assertEquals("a_b * c", fallback)
    }

    @Test
    fun `a multi-line bracket formula keeps its inner content verbatim`() {
        val fallback = FormulaText.fallback(listOf("\\[", "a_b * c", "\\]"))
        assertEquals("a_b * c", fallback)
    }

    @Test
    fun `an unclosed multi-line formula still returns its content instead of crashing`() {
        val fallback = FormulaText.fallback(listOf("$$", "still going"))
        assertEquals("still going", fallback)
    }

    @Test
    fun `underscores and asterisks inside a formula are never touched`() {
        val fallback = FormulaText.fallback(listOf("$$", "f(x) = x_1 * x_2", "$$"))
        assertEquals("f(x) = x_1 * x_2", fallback)
    }
}
