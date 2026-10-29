package com.danielealbano.androidremotecontrolmcp.services.remote.hooks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class InterruptionsTest {
    private val now = ZonedDateTime.of(2026, 10, 19, 14, 0, 0, 0, ZoneId.of("Asia/Shanghai"))

    @Test
    fun `chinese reset text gives the wait until the reset`() {
        val result = Interruptions.classify("API Error: 用量已满，约 1 小时 30 分钟后重置", now)
        assertEquals(Interruption(InterruptionKind.LIMIT, 90 * 60_000L), result)
    }

    @Test
    fun `chinese reset text with only minutes`() {
        val result = Interruptions.classify("额度用完，约 12 分钟后重置", now)
        assertEquals(Interruption(InterruptionKind.LIMIT, 12 * 60_000L), result)
    }

    @Test
    fun `claude epoch reset is read relative to now`() {
        val reset = now.toEpochSecond() + 600
        val result = Interruptions.classify("Claude AI usage limit reached|$reset", now)
        assertEquals(Interruption(InterruptionKind.LIMIT, 600_000L), result)
    }

    @Test
    fun `clock reset later today`() {
        val result = Interruptions.classify("5-hour limit reached ∙ resets 3:30pm", now)
        assertEquals(Interruption(InterruptionKind.LIMIT, 90 * 60_000L), result)
    }

    @Test
    fun `clock reset already passed today means tomorrow`() {
        val result = Interruptions.classify("limit reached, resets 1pm", now)
        assertEquals(Interruption(InterruptionKind.LIMIT, 23 * 3_600_000L), result)
    }

    @Test
    fun `limit without a time waits the default`() {
        val result = Interruptions.classify("You have hit your usage limit.", now)
        assertEquals(Interruption(InterruptionKind.LIMIT, Interruptions.UNKNOWN_RESET_MS), result)
    }

    @Test
    fun `busy rate and crash are told apart`() {
        assertEquals(InterruptionKind.BUSY, Interruptions.classify("Selected model is at capacity", now)?.kind)
        assertEquals(InterruptionKind.BUSY, Interruptions.classify("error 529 overloaded_error", now)?.kind)
        assertEquals(InterruptionKind.RATE, Interruptions.classify("HTTP 429 Too Many Requests", now)?.kind)
        val crash = "Claude stopped unexpectedly (exit code 1)"
        assertEquals(InterruptionKind.CRASH, Interruptions.classify(crash, now)?.kind)
    }

    @Test
    fun `errors that do not go away by themselves are not retried`() {
        assertNull(Interruptions.classify("403 This account only allows Codex official clients", now))
        assertNull(Interruptions.classify("401 Unauthorized: invalid api key", now))
        assertNull(Interruptions.classify("No conversation found with session ID", now))
        assertNull(Interruptions.classify("Prompt is too long", now))
        assertNull(Interruptions.classify("", now))
        assertNull(Interruptions.classify("done, all tests pass", now))
    }

    @Test
    fun `pacing waits for the limit reset plus a margin`() {
        val wait = ResumePacing.waitMs(Interruption(InterruptionKind.LIMIT, 60_000L), 0)
        assertEquals(60_000L + ResumePacing.LIMIT_MARGIN_MS, wait)
    }

    @Test
    fun `pacing backs off and stays at the last step`() {
        val busy = Interruption(InterruptionKind.BUSY)
        assertEquals(3_000L, ResumePacing.waitMs(busy, 0))
        assertEquals(5_000L, ResumePacing.waitMs(busy, 1))
        assertEquals(60_000L, ResumePacing.waitMs(busy, 30))
        assertEquals(60_000L, ResumePacing.waitMs(Interruption(InterruptionKind.RATE), 0))
        assertEquals(10_000L, ResumePacing.waitMs(Interruption(InterruptionKind.CRASH), 0))
    }

    @Test
    fun `pacing gives up after the hourly cap`() {
        assertNull(ResumePacing.waitMs(Interruption(InterruptionKind.CRASH), 3))
        assertNull(ResumePacing.waitMs(Interruption(InterruptionKind.LIMIT, 1), 3))
        assertNull(ResumePacing.waitMs(Interruption(InterruptionKind.BUSY), 60))
        assertNull(ResumePacing.waitMs(Interruption(InterruptionKind.RATE), 10))
    }
}
