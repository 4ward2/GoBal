package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("StreamingText")
class StreamingTextTest {
    @Test
    fun `plain text is shown as it is`() {
        assertEquals("今天有点冷", StreamingText.visible("今天有点冷"))
    }

    @Test
    fun `a finished status tag never shows`() {
        assertEquals("哼", StreamingText.visible("哼\n<status>生闷气中</status>"))
    }

    @Test
    fun `a status tag that is still being written never shows`() {
        assertEquals("哼", StreamingText.visible("哼\n<status>生闷"))
        assertEquals("哼", StreamingText.visible("哼\n<sta"))
        assertEquals("哼", StreamingText.visible("哼\n<"))
    }

    @Test
    fun `thinking is read apart from the answer, in each service's own field`() {
        val openai = ProviderKind.OPENAI
        val deepseek = """data: {"choices":[{"delta":{"reasoning_content":"先想想","content":null}}]}"""
        assertEquals("先想想", StreamingReplies.reasoning(openai, deepseek))
        assertEquals("", StreamingReplies.delta(openai, deepseek))
        val other = """data: {"choices":[{"delta":{"reasoning":"再想想"}}]}"""
        assertEquals("再想想", StreamingReplies.reasoning(openai, other))
        val answer = """data: {"choices":[{"delta":{"content":"好"}}]}"""
        assertEquals("", StreamingReplies.reasoning(openai, answer))
        val claude = """data: {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"嗯"}}"""
        assertEquals("嗯", StreamingReplies.reasoning(ProviderKind.ANTHROPIC, claude))
        assertEquals("", StreamingReplies.delta(ProviderKind.ANTHROPIC, claude))
        assertEquals("", StreamingReplies.reasoning(openai, "data: [DONE]"))
        assertEquals("", StreamingReplies.reasoning(openai, ": keep-alive"))
    }

    @Test
    fun `other angle brackets in the middle of the text stay`() {
        assertEquals("a < b 且 b > c", StreamingText.visible("a < b 且 b > c"))
    }
}
