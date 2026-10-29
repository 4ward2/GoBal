package com.danielealbano.androidremotecontrolmcp.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("LocalText")
class LocalTextTest {
    @Test
    fun `late echoes of the field's own typing never overwrite newer text`() {
        val field = LocalText("")
        field.typed("s", "s")
        field.typed("sk", "sk")
        field.typed("sk-", "sk-")
        field.sync("s")
        field.sync("sk")
        assertEquals("sk-", field.text)
        field.sync("sk-")
        assertEquals("sk-", field.text)
    }

    @Test
    fun `a conflated echo that skips values still counts as an echo`() {
        val field = LocalText("")
        field.typed("a", "a")
        field.typed("ab", "ab")
        field.typed("abc", "abc")
        field.sync("ab")
        assertEquals("abc", field.text)
    }

    @Test
    fun `a change made elsewhere replaces the text`() {
        val field = LocalText("old")
        field.typed("old!", "old!")
        field.sync("pasted-key")
        assertEquals("pasted-key", field.text)
        field.sync("old!")
        assertEquals("old!", field.text)
    }

    @Test
    fun `the value sent on may differ from what is shown`() {
        val field = LocalText("")
        field.typed("  key ", "key")
        field.sync("key")
        assertEquals("  key ", field.text)
    }
}
