package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ProviderForm")
class ProviderFormTest {
    @Test
    fun `a form needs a name and an http address`() {
        assertFalse(ProviderForm(name = "x").valid)
        assertFalse(ProviderForm(name = "x", baseUrl = "api.x.com").valid)
        assertTrue(ProviderForm(name = "x", baseUrl = " https://api.x.com/v1/ ").valid)
    }

    @Test
    fun `models are split on commas and newlines, trimmed, and deduplicated`() {
        val form = ProviderForm(name = " 测试 ", kind = ProviderKind.ANTHROPIC, baseUrl = "https://a.com/")
        val provider = form.copy(models = "a, b\nc，a, ").toProvider()
        assertEquals("测试", provider.name)
        assertEquals("https://a.com", provider.baseUrl)
        assertEquals(listOf("a", "b", "c"), provider.models)
        assertTrue(provider.id.isNotBlank())
        assertEquals(provider.models.joinToString(", "), ProviderForm.of(provider).models)
    }

    @Test
    fun `fetched context windows are kept only for models still in the list`() {
        val contexts = mapOf("a" to 1000, "gone" to 5)
        val form = ProviderForm(name = "x", baseUrl = "https://a.com", models = "a, b", contexts = contexts)
        val provider = form.toProvider()
        assertEquals(mapOf("a" to 1000), provider.modelContexts)
        assertEquals(mapOf("a" to 1000), ProviderForm.of(provider).contexts)
    }
}
