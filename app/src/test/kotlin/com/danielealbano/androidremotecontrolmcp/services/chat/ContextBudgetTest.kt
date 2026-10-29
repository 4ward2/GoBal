package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ContextBudget")
class ContextBudgetTest {
    private val provider = ModelProvider("p", "x", ProviderKind.OPENAI, "https://x", "k", listOf("m"))

    @Test
    fun `cjk counts one token per character and latin four characters per token`() {
        assertEquals(4, ContextBudget.tokens("你好世界"))
        assertEquals(3, ContextBudget.tokens("hello world"))
        assertEquals(0, ContextBudget.tokens(""))
        assertEquals(4 + 3, ContextBudget.tokens("你好世界hello world"))
    }

    @Test
    fun `a request adds the system prompt, the turns with overhead, and the draft`() {
        val total = ContextBudget.tokens(system = "你是猫", turns = listOf("喵", "在吗"), draft = "hi")
        assertEquals(3 + (1 + 4) + (2 + 4) + 1, total)
    }

    @Test
    fun `window comes from the provider, then the name, then the family table, then the default`() {
        assertEquals(50_000, ContextBudget.window(provider.copy(contextWindow = 50_000), "gpt-4o"))
        assertEquals(32 * 1024, ContextBudget.window(provider, "qwen-plus-32k"))
        assertEquals(200_000, ContextBudget.window(provider, "claude-sonnet-4-5"))
        assertEquals(8_192, ContextBudget.window(provider, "gpt-4-0613"))
        assertEquals(ContextBudget.DEFAULT_WINDOW, ContextBudget.window(null, "some-model"))
    }

    @Test
    fun `a window the service reported beats the name and the table, but not one typed in`() {
        val reported = provider.copy(modelContexts = mapOf("gpt-4o" to 64_000))
        assertEquals(64_000, ContextBudget.window(reported, "gpt-4o"))
        assertEquals(128_000, ContextBudget.window(reported, "gpt-4o-mini"))
        assertEquals(10_000, ContextBudget.window(reported.copy(contextWindow = 10_000), "gpt-4o"))
    }

    @Test
    fun `current generations use their published windows, most specific name first`() {
        assertEquals(1_000_000, ContextBudget.window(null, "mimo-v2.5"))
        assertEquals(1_000_000, ContextBudget.window(null, "deepseek/deepseek-v4-flash"))
        assertEquals(128_000, ContextBudget.window(null, "deepseek-chat"))
        assertEquals(262_144, ContextBudget.window(null, "Qwen3.8-27B"))
        assertEquals(1_000_000, ContextBudget.window(null, "qwen3.8-max"))
        assertEquals(256_000, ContextBudget.window(null, "kimi-k2.6"))
        assertEquals(200_000, ContextBudget.window(null, "claude-haiku-4-5-20251001"))
        assertEquals(1_000_000, ContextBudget.window(null, "claude-opus-4-8"))
        assertEquals(400_000, ContextBudget.window(null, "gpt-5.4-mini"))
    }

    @Test
    fun `a conversation is cut to the window, keeping the system prompt short and the newest turns`() {
        val system = ChatMessage("system", "设".repeat(1000))
        val turns = (1..20).map { ChatMessage(if (it % 2 == 0) "assistant" else "user", "第${it}句" + "话".repeat(40)) }
        val fitted = ContextBudget.fit(listOf(system) + turns, window = 400)
        assertEquals("system", fitted.first().role)
        assertTrue(ContextBudget.tokens(fitted.first().text) <= 150)
        assertEquals(turns.last(), fitted.last())
        assertTrue(fitted.size < 21)
        assertTrue(fitted.drop(1).zipWithNext().all { (a, b) -> turns.indexOf(a) < turns.indexOf(b) })
        val total = fitted.sumOf { ContextBudget.tokens(it.text) + 4 }
        assertTrue(total <= 300, "total $total")
    }

    @Test
    fun `the newest turn stays even when it alone is too long, and short chats pass untouched`() {
        val long = ChatMessage("user", "长".repeat(900))
        assertEquals(listOf(long), ContextBudget.fit(listOf(ChatMessage("user", "早"), long), window = 400))
        val short = listOf(ChatMessage("system", "s"), ChatMessage("user", "hi"))
        assertEquals(short, ContextBudget.fit(short, window = 4096))
    }

    @Test
    fun `labels are short`() {
        assertEquals("1M", ContextBudget.label(1_048_576))
        assertEquals("262K", ContextBudget.label(262_144))
        assertEquals("8K", ContextBudget.label(8_192))
        assertEquals("512", ContextBudget.label(512))
    }

    @Test
    fun `levels switch at 75 and 95 percent`() {
        assertEquals(ContextLevel.OK, ContextBudget.level(ContextBudget.ratio(74, 100)))
        assertEquals(ContextLevel.WARN, ContextBudget.level(ContextBudget.ratio(75, 100)))
        assertEquals(ContextLevel.WARN, ContextBudget.level(ContextBudget.ratio(94, 100)))
        assertEquals(ContextLevel.CRITICAL, ContextBudget.level(ContextBudget.ratio(95, 100)))
        assertEquals(1f, ContextBudget.ratio(500, 100))
        assertEquals(0f, ContextBudget.ratio(5, 0))
    }
}
