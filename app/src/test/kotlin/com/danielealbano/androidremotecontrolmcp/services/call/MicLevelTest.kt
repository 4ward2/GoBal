package com.danielealbano.androidremotecontrolmcp.services.call

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MicLevel")
class MicLevelTest {
    @Test
    fun `reports the loudest sample once per window and then starts over`() {
        val level = MicLevel(windowMs = 100)
        assertNull(level.add(shortArrayOf(10, -300, 20), 3, nowMs = 0))
        assertNull(level.add(shortArrayOf(50), 1, nowMs = 50))
        assertEquals(300, level.add(shortArrayOf(5), 1, nowMs = 100))
        assertEquals(7, level.add(shortArrayOf(7, 0), 2, nowMs = 200))
    }

    @Test
    fun `silence reads as zero and only the counted samples matter`() {
        val level = MicLevel(windowMs = 10)
        level.add(ShortArray(4), 4, nowMs = 0)
        assertEquals(0, level.add(shortArrayOf(0, 9999), 1, nowMs = 10))
    }
}
