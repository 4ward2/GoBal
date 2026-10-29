package com.danielealbano.androidremotecontrolmcp.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Appearance")
class AppearanceTest {
    @Test
    fun `following the phone is dark exactly when the phone is`() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
    }

    @Test
    fun `a chosen palette ignores the phone`() {
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
    }

    @Test
    fun `stored codes read back, and anything unknown falls back to the defaults`() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.of(it.code)) }
        TextSize.entries.forEach { assertEquals(it, TextSize.of(it.code)) }
        assertEquals(ThemeMode.SYSTEM, ThemeMode.of(null))
        assertEquals(TextSize.STANDARD, TextSize.of("huge"))
        assertEquals(Appearance(ThemeMode.SYSTEM, TextSize.STANDARD), Appearance())
    }

    @Test
    fun `each text size is larger than the one before`() {
        val scales = TextSize.entries.map { it.scale }
        assertEquals(1f, scales.first())
        assertEquals(scales.sorted(), scales)
        assertEquals(scales.size, scales.toSet().size)
    }
}
