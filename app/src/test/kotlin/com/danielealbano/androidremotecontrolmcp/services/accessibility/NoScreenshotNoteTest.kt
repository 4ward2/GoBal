package com.danielealbano.androidremotecontrolmcp.services.accessibility

import com.danielealbano.androidremotecontrolmcp.utils.ApiLevel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class NoScreenshotNoteTest {
    @AfterEach
    fun newestAgain() {
        ApiLevel.pretend(null)
    }

    @Test
    fun `a phone that can take screenshots is told to ask for one`() {
        ApiLevel.pretend(30)
        assertEquals(CompactTreeFormatter.NOTE_LINE_CUSTOM_ELEMENTS, CompactTreeFormatter.customElementsNote())
    }

    @Test
    fun `a phone that cannot is never told to ask for one`() {
        ApiLevel.pretend(29)
        val note = CompactTreeFormatter.customElementsNote()
        assertEquals(CompactTreeFormatter.NOTE_LINE_NO_SCREENSHOT, note)
        assertFalse(note.contains("include_screenshot"))
    }
}
