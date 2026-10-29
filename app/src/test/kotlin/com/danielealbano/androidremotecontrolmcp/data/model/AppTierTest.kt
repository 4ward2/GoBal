package com.danielealbano.androidremotecontrolmcp.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AppTier")
class AppTierTest {
    @Test
    fun `of maps "simple" to SIMPLE`() {
        assertEquals(AppTier.SIMPLE, AppTier.of("simple"))
    }

    @Test
    fun `of maps "office" to OFFICE`() {
        assertEquals(AppTier.OFFICE, AppTier.of("office"))
    }

    @Test
    fun `of maps "developer" to DEVELOPER`() {
        assertEquals(AppTier.DEVELOPER, AppTier.of("developer"))
    }

    @Test
    fun `of returns SIMPLE for unknown code`() {
        assertEquals(AppTier.SIMPLE, AppTier.of("unknown"))
    }

    @Test
    fun `of returns SIMPLE for null code`() {
        assertEquals(AppTier.SIMPLE, AppTier.of(null))
    }

    @Test
    fun `atLeast DEVELOPER at least OFFICE returns true`() {
        assertTrue(AppTier.DEVELOPER.atLeast(AppTier.OFFICE))
    }

    @Test
    fun `atLeast OFFICE at least OFFICE returns true`() {
        assertTrue(AppTier.OFFICE.atLeast(AppTier.OFFICE))
    }

    @Test
    fun `atLeast SIMPLE at least OFFICE returns false`() {
        assertFalse(AppTier.SIMPLE.atLeast(AppTier.OFFICE))
    }

    @Test
    fun `atLeast DEVELOPER at least DEVELOPER returns true`() {
        assertTrue(AppTier.DEVELOPER.atLeast(AppTier.DEVELOPER))
    }

    @Test
    fun `atLeast SIMPLE at least SIMPLE returns true`() {
        assertTrue(AppTier.SIMPLE.atLeast(AppTier.SIMPLE))
    }

    @Test
    fun `atLeast OFFICE at least DEVELOPER returns false`() {
        assertFalse(AppTier.OFFICE.atLeast(AppTier.DEVELOPER))
    }
}
