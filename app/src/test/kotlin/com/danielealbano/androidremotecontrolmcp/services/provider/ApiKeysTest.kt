package com.danielealbano.androidremotecontrolmcp.services.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ApiKeys")
class ApiKeysTest {
    @Test
    fun `each distinctive shape points to its service`() {
        val cases =
            mapOf(
                "sk-ant-api03-" + "a1B2".repeat(8) to "anthropic",
                "sk-or-v1-" + "0f".repeat(16) to "openrouter",
                "sk-proj-" + "Q9w8".repeat(8) to "openai",
                "AIza" + "Sy0".repeat(12) to "gemini",
                "gsk_" + "k3y".repeat(10) to "groq",
                "xai-" + "z9".repeat(15) to "xai",
                "0123456789abcdef".repeat(2) + ".AbCdEfGh12345678" to "zhipu",
                "user_" + "A1b2C3".repeat(10) to "commandcode",
            )
        cases.forEach { (key, preset) -> assertEquals(FoundKey(key, preset), ApiKeys.find("key: $key", false), key) }
    }

    @Test
    fun `a plain sk key could be many services`() {
        val key = "sk-" + "a1".repeat(16)
        assertEquals(FoundKey(key, null), ApiKeys.find(key, aboutKeys = false))
    }

    @Test
    fun `sk inside a longer word is not a key`() {
        assertNull(ApiKeys.find("task-" + "a1".repeat(16), aboutKeys = false))
    }

    @Test
    fun `generic long tokens count only when the sentence is about keys and mix letters with digits`() {
        val token = "Z".repeat(10) + "9".repeat(30)
        assertNull(ApiKeys.find(token, aboutKeys = false))
        assertEquals(token, ApiKeys.find(token, aboutKeys = true)?.key)
        assertNull(ApiKeys.find("a".repeat(40), aboutKeys = true))
        assertNull(ApiKeys.find("1".repeat(40), aboutKeys = true))
    }
}
