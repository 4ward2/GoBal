package com.danielealbano.androidremotecontrolmcp.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApiLevelTest {
    @AfterEach
    fun newestAgain() {
        ApiLevel.pretend(null)
    }

    @Test
    fun `with no phone to ask it is the newest Android`() {
        assertTrue(ApiLevel.atLeast(33))
        assertTrue(ApiLevel.atLeast(99))
    }

    @Test
    fun `a pretended phone answers for its own level`() {
        ApiLevel.pretend(29)
        assertEquals(29, ApiLevel.current)
        assertTrue(ApiLevel.atLeast(29))
        assertFalse(ApiLevel.atLeast(30))
    }
}
