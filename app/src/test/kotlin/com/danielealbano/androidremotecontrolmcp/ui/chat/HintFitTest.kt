package com.danielealbano.androidremotecontrolmcp.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HintFitTest {
    private val widths = listOf(300, 200, 80)

    @Test
    fun `the full sentence is used when it fits`() {
        assertEquals(0, HintFit.pick(widths, 300))
        assertEquals(0, HintFit.pick(widths, 500))
    }

    @Test
    fun `a narrower box gets the longest wording that still fits`() {
        assertEquals(1, HintFit.pick(widths, 299))
        assertEquals(2, HintFit.pick(widths, 199))
    }

    @Test
    fun `when nothing fits the shortest is shown, to be cut off rather than wrapped`() {
        assertEquals(2, HintFit.pick(widths, 10))
    }
}
