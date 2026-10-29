package com.danielealbano.androidremotecontrolmcp.services.call

import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import com.danielealbano.androidremotecontrolmcp.services.chat.StreamingReplies
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Call engine logic")
class CallLogicTest {
    private val filter = EchoFilter(delayMs = 150, slackMs = 500)
    private val spoken = listOf(SpokenSpan("要不要喝点水", 1000, 2500), SpokenSpan("我给你倒一杯", 2500, 4000))

    @Test
    fun `the phone hearing its own sentence at the acoustic delay is an echo`() {
        val heard = HeardSegment("要 不要 喝点 水", 1200, 2700)
        assertTrue(filter.isEcho(heard, spoken))
    }

    @Test
    fun `the user repeating the phone's words later is not an echo`() {
        val later = HeardSegment("喝点水", 3800, 4600)
        assertFalse(filter.isEcho(later, spoken))
        val different = HeardSegment("我不想喝水现在", 1200, 2700)
        assertFalse(filter.isEcho(different, spoken))
    }

    @Test
    fun `short utterances are never judged by text`() {
        assertFalse(filter.isEcho(HeardSegment("喝水", 1200, 1800), spoken))
    }

    @Test
    fun `speech while the phone talks waits until it has finished`() {
        val turns = TurnController(pauseMs = 700)
        turns.onAssistantStarted()
        turns.onAssistantSpeaking()
        assertEquals(TurnAction.None, turns.onUserFinal("等一下", 1000))
        assertEquals(TurnAction.None, turns.onUserFinal("我还有个问题", 1500))
        assertEquals(TurnAction.None, turns.onTick(5000))
        assertEquals(TurnAction.Send("等一下 我还有个问题"), turns.onAssistantFinished())
        assertEquals(CallPhase.THINKING, turns.phase)
    }

    @Test
    fun `while listening a pause ends the turn`() {
        val turns = TurnController(pauseMs = 700)
        turns.onUserFinal("今天", 1000)
        assertEquals(TurnAction.None, turns.onTick(1500))
        turns.onUserFinal("天气怎么样", 1600)
        assertEquals(TurnAction.None, turns.onTick(2000))
        assertEquals(TurnAction.Send("今天 天气怎么样"), turns.onTick(2400))
        assertEquals(TurnAction.None, turns.onAssistantFinished())
        assertEquals(CallPhase.LISTENING, turns.phase)
    }

    @Test
    fun `sentences are cut as they complete and markdown is stripped`() {
        val splitter = SentenceSplitter()
        assertEquals(emptyList<String>(), splitter.feed("你好，**今天"))
        assertEquals(listOf("你好，今天天气不错。"), splitter.feed("天气**不错。那我"))
        assertEquals(listOf("那我们出去走走？"), splitter.feed("们出去走走？<sta"))
        assertEquals(emptyList<String>(), splitter.feed("tus>开心</status>好的"))
        assertEquals(listOf("好的"), splitter.finish())
        assertEquals(emptyList<String>(), splitter.finish())
    }

    @Test
    fun `sse lines yield deltas for both wire formats`() {
        val openAi = """data: {"choices":[{"delta":{"content":"你好"}}]}"""
        assertEquals("你好", StreamingReplies.delta(ProviderKind.OPENAI, openAi))
        assertEquals("", StreamingReplies.delta(ProviderKind.OPENAI, ": keep-alive"))
        assertNull(StreamingReplies.delta(ProviderKind.OPENAI, "data: [DONE]"))
        val anthropic = """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"嗨"}}"""
        assertEquals("嗨", StreamingReplies.delta(ProviderKind.ANTHROPIC, anthropic))
        assertNull(StreamingReplies.delta(ProviderKind.ANTHROPIC, """data: {"type":"message_stop"}"""))
        assertEquals("""{"stream":true,"model":"m"}""", StreamingReplies.streamingBody("""{"model":"m"}"""))
    }
}
