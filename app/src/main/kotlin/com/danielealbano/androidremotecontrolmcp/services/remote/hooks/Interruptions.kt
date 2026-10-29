package com.danielealbano.androidremotecontrolmcp.services.remote.hooks

import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/** Why an agent on a computer stopped in a way that is worth simply trying again. */
enum class InterruptionKind {
    /** A usage limit with a reset time: wait for the reset. */
    LIMIT,

    /** The model is at capacity or the service is overloaded: try again within seconds. */
    BUSY,

    /** Rate limited without a reset time: try again within minutes. */
    RATE,

    /** The agent's program died: restart it a few times, then leave it to the user. */
    CRASH,
}

/** An interruption, and for [InterruptionKind.LIMIT] how long until the limit resets (else 0). */
data class Interruption(
    val kind: InterruptionKind,
    val resetInMs: Long = 0,
)

/**
 * Reads an agent's error (or its last reply, where Claude Code puts usage-limit errors) and says
 * whether it is an interruption that goes away by itself. Anything else — a wrong key, a refused
 * account, a conversation that no longer exists, a prompt too long — is not retried. Pure.
 */
object Interruptions {
    private val NEVER =
        listOf(
            "401",
            "403",
            "unauthorized",
            "forbidden",
            "invalid api key",
            "only allows",
            "no conversation found",
            "prompt is too long",
            "context length",
            "context window",
        )
    private val BUSY_WORDS =
        listOf("at capacity", "overloaded", "server_overloaded", "529", "503", "temporarily unavailable")
    private val RATE_WORDS = listOf("rate limit", "rate_limit", "429", "too many requests")
    private val CRASH_WORDS = listOf("stopped unexpectedly", "exit code", "process exited", "crashed")
    private val LIMIT_WORDS = listOf("usage limit", "limit reached", "重置", "额度")

    // 「约 N 小时 M 分钟后重置」, as some gateways in front of Claude say it.
    private val RESET_IN = Regex("约\\s*(?:(\\d+)\\s*小时)?\\s*(?:(\\d+)\\s*分钟)?\\s*后重置")

    // Claude Code: "Claude AI usage limit reached|1792400000" (epoch seconds of the reset).
    private val RESET_EPOCH = Regex("limit reached\\|(\\d{10})")

    // Claude Code: "5-hour limit reached ∙ resets 3pm" / "resets 3:30pm".
    private val RESET_CLOCK = Regex("resets\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)", RegexOption.IGNORE_CASE)

    private const val MINUTE_MS = 60_000L
    private const val HALF_DAY_HOURS = 12

    /** When the limit text names no time: try again after this long. */
    const val UNKNOWN_RESET_MS = 30 * MINUTE_MS
    private val UNKNOWN_LIMIT = Interruption(InterruptionKind.LIMIT, UNKNOWN_RESET_MS)
    private const val MINUTES_PER_HOUR = 60
    private const val MERIDIEM = 3

    fun classify(
        text: String,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): Interruption? {
        val lower = text.lowercase()
        val limit = resetIn(text, now)
        return when {
            text.isBlank() || NEVER.any { it in lower } -> null
            limit != null -> Interruption(InterruptionKind.LIMIT, limit)
            LIMIT_WORDS.any { it in lower } && "limit" in lower -> UNKNOWN_LIMIT
            BUSY_WORDS.any { it in lower } -> Interruption(InterruptionKind.BUSY)
            RATE_WORDS.any { it in lower } -> Interruption(InterruptionKind.RATE)
            CRASH_WORDS.any { it in lower } -> Interruption(InterruptionKind.CRASH)
            else -> null
        }
    }

    /** How long until the limit [text] speaks of resets, or null when it names no time. */
    private fun resetIn(
        text: String,
        now: ZonedDateTime,
    ): Long? = resetAfter(text) ?: resetAtEpoch(text, now) ?: RESET_CLOCK.find(text)?.let { clock(it, now) }

    private fun resetAfter(text: String): Long? =
        RESET_IN.find(text)?.let { match ->
            val hours = match.groupValues[1].toLongOrNull() ?: 0
            val minutes = match.groupValues[2].toLongOrNull() ?: 0
            ((hours * MINUTES_PER_HOUR + minutes) * MINUTE_MS).takeIf { it > 0 }
        }

    private fun resetAtEpoch(
        text: String,
        now: ZonedDateTime,
    ): Long? =
        RESET_EPOCH.find(text)?.let { match ->
            val at = Instant.ofEpochSecond(match.groupValues[1].toLong())
            (at.toEpochMilli() - now.toInstant().toEpochMilli()).coerceAtLeast(0)
        }

    private fun clock(
        match: MatchResult,
        now: ZonedDateTime,
    ): Long {
        val hour12 = match.groupValues[1].toInt() % HALF_DAY_HOURS
        val hour = if (match.groupValues[MERIDIEM].equals("pm", ignoreCase = true)) hour12 + HALF_DAY_HOURS else hour12
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        var at = now.with(LocalTime.of(hour, minute))
        if (!at.isAfter(now)) at = at.plusDays(1)
        return at.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()
    }
}

/**
 * How long to wait before the next try, by kind and by how many tries this agent already had in
 * the last hour; null once that is too many, and the agent is left for the user. Pure.
 */
class ResumePacing private constructor() {
    companion object {
        private const val SECOND_MS = 1_000L
        private const val MINUTE_MS = 60_000L

        /** After the reset, a margin: limits are counted on the provider's clock, not ours. */
        const val LIMIT_MARGIN_MS = 2 * MINUTE_MS

        private val BUSY_STEPS = listOf(3, 5, 8, 15, 30, 60).map { it * SECOND_MS }
        private val RATE_STEPS = listOf(1, 2, 5, 10).map { it * MINUTE_MS }
        private val CRASH_STEPS = listOf(10 * SECOND_MS, MINUTE_MS, 5 * MINUTE_MS)

        private val PER_HOUR =
            mapOf(
                InterruptionKind.LIMIT to 3,
                InterruptionKind.BUSY to 60,
                InterruptionKind.RATE to 10,
                InterruptionKind.CRASH to 3,
            )

        fun waitMs(
            interruption: Interruption,
            triesLastHour: Int,
        ): Long? {
            if (triesLastHour >= (PER_HOUR[interruption.kind] ?: 0)) return null
            return when (interruption.kind) {
                InterruptionKind.LIMIT -> interruption.resetInMs + LIMIT_MARGIN_MS
                InterruptionKind.BUSY -> BUSY_STEPS[triesLastHour.coerceAtMost(BUSY_STEPS.lastIndex)]
                InterruptionKind.RATE -> RATE_STEPS[triesLastHour.coerceAtMost(RATE_STEPS.lastIndex)]
                InterruptionKind.CRASH -> CRASH_STEPS[triesLastHour.coerceAtMost(CRASH_STEPS.lastIndex)]
            }
        }
    }
}
