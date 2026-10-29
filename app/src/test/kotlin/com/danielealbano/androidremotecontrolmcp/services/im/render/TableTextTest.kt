package com.danielealbano.androidremotecontrolmcp.services.im.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TableText")
class TableTextTest {
    @Test
    fun `each data row becomes column colon value lines with a blank line between rows`() {
        val lines = listOf("| 版本 | 大小 |", "|---|---|", "| 1.0 | 10MB |", "| 2.0 | 12MB |")
        val text = TableText.fallback(lines)
        assertEquals("（表格，共 2 行）\n\n版本：1.0\n大小：10MB\n\n版本：2.0\n大小：12MB", text)
    }

    @Test
    fun `a table with no data rows still reports its row count`() {
        val lines = listOf("| a | b |", "|---|---|")
        assertEquals("（表格，共 0 行）", TableText.fallback(lines))
    }

    @Test
    fun `cells without surrounding pipes are parsed the same way`() {
        val lines = listOf("a | b", "---|---", "1 | 2")
        assertEquals("（表格，共 1 行）\n\na：1\nb：2", TableText.fallback(lines))
    }

    @Test
    fun `an escaped pipe inside a cell is restored and does not split the row`() {
        val lines = listOf("| name |", "|---|", "| a \\| b |")
        assertEquals("（表格，共 1 行）\n\nname：a | b", TableText.fallback(lines))
    }

    @Test
    fun `inline markers inside a cell are stripped`() {
        val lines = listOf("| name |", "|---|", "| **bold** |")
        assertEquals("（表格，共 1 行）\n\nname：bold", TableText.fallback(lines))
    }

    @Test
    fun `the fallback text never contains a pipe character`() {
        val lines = listOf("| a | b |", "|---|---|", "| 1 | 2 |", "| 3 | 4 |")
        assertFalse(TableText.fallback(lines).contains('|'))
    }
}
