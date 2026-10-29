package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider

/** How full the model's context is, for the ring next to the composer's "+" button. */
enum class ContextLevel {
    /** Under [ContextBudget.WARN]: plenty of room. */
    OK,

    /** Between [ContextBudget.WARN] and [ContextBudget.CRITICAL]: getting tight. */
    WARN,

    /** At or above [ContextBudget.CRITICAL]: the next message may be cut off. */
    CRITICAL,
}

/**
 * Estimates how much of the model's context window the next request will use. Token counts are
 * approximations (there is no tokenizer on the phone): a CJK character is about one token, other
 * text about four characters per token. Good enough for a "how full is it" ring.
 */
object ContextBudget {
    const val WARN = 0.75f
    const val CRITICAL = 0.95f

    /** Used when neither the provider nor the model name says otherwise. */
    const val DEFAULT_WINDOW = 128_000

    private const val CHARS_PER_TOKEN = 4
    private const val KILO = 1024

    /** Rough per-turn overhead for role markers and separators. */
    private const val TURN_OVERHEAD = 4

    /** "…-32k", "…-128k" and the like in a model name. */
    private val WINDOW_IN_NAME = Regex("(\\d{1,4})k(?![a-z0-9])", RegexOption.IGNORE_CASE)

    private const val WINDOW_8K = 8_192
    private const val WINDOW_16K = 16_385
    private const val WINDOW_128K = 128_000
    private const val WINDOW_200K = 200_000
    private const val WINDOW_256K = 256_000
    private const val WINDOW_262K = 262_144
    private const val WINDOW_400K = 400_000
    private const val WINDOW_500K = 500_000
    private const val WINDOW_1M = 1_000_000
    private const val WINDOW_1050K = 1_050_000

    /**
     * Well-known families, most specific first (the first contained prefix wins). Current
     * generations follow the vendors' published limits; older names keep their original windows.
     */
    private val KNOWN_WINDOWS =
        listOf(
            "gpt-6" to WINDOW_1050K,
            "gpt-5.6" to WINDOW_1050K,
            "gpt-5" to WINDOW_400K,
            "gpt-4o" to WINDOW_128K,
            "gpt-4.1" to WINDOW_1M,
            "gpt-4-turbo" to WINDOW_128K,
            "gpt-4" to WINDOW_8K,
            "gpt-3.5" to WINDOW_16K,
            "o1" to WINDOW_200K,
            "o3" to WINDOW_200K,
            "claude-3" to WINDOW_200K,
            "claude-haiku" to WINDOW_200K,
            "claude-sonnet-4-6" to WINDOW_1M,
            "claude-opus-4-7" to WINDOW_1M,
            "claude-opus-4-8" to WINDOW_1M,
            "claude-opus-5" to WINDOW_1M,
            "claude-sonnet-5" to WINDOW_1M,
            "claude-fable" to WINDOW_1M,
            "claude" to WINDOW_200K,
            "gemini" to WINDOW_1M,
            "deepseek-v4" to WINDOW_1M,
            "deepseek" to WINDOW_128K,
            "qwen3.8-27b" to WINDOW_262K,
            "qwen3.7" to WINDOW_1M,
            "qwen3.8" to WINDOW_1M,
            "qwen" to WINDOW_128K,
            "glm-5.2" to WINDOW_1M,
            "glm-5.3" to WINDOW_1M,
            "glm-5" to WINDOW_200K,
            "glm" to WINDOW_128K,
            "mimo-v2" to WINDOW_1M,
            "mimo" to WINDOW_128K,
            "kimi-k3" to WINDOW_1M,
            "kimi-k2" to WINDOW_256K,
            "minimax-m3" to WINDOW_1M,
            "minimax-m2" to WINDOW_200K,
            "grok-4" to WINDOW_500K,
            "step-3.5" to WINDOW_1M,
            "step-3.7" to WINDOW_256K,
            "hy4" to WINDOW_1M,
            "hy3" to WINDOW_262K,
        )

    fun tokens(text: String): Int {
        var cjk = 0
        var other = 0
        text.forEach { ch -> if (isWide(ch)) cjk++ else other++ }
        return cjk + (other + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }

    /** Tokens of a whole request: the system prompt plus every turn plus the draft being typed. */
    fun tokens(
        system: String,
        turns: List<String>,
        draft: String,
    ): Int = tokens(system) + turns.sumOf { tokens(it) + TURN_OVERHEAD } + tokens(draft)

    /**
     * The context window for [model]: a window typed in for the provider first, then what the
     * service reported for this model, then a "-32k"-style hint in the name, then a table of
     * well-known families, then [DEFAULT_WINDOW].
     */
    fun window(
        provider: ModelProvider?,
        model: String,
    ): Int {
        val configured = provider?.contextWindow?.takeIf { it > 0 }
        val reported = provider?.modelContexts?.get(model)?.takeIf { it > 0 }
        if (configured != null || reported != null) return configured ?: requireNotNull(reported)
        val name = model.lowercase()
        val inName =
            WINDOW_IN_NAME
                .find(name)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.let { it * KILO }
        val known = KNOWN_WINDOWS.firstOrNull { (prefix, _) -> name.contains(prefix) }?.second
        return inName ?: known ?: DEFAULT_WINDOW
    }

    /** "1M", "256K", "8K": short enough for a drop-down row. */
    fun label(window: Int): String =
        when {
            window >= MILLION -> "${window / MILLION}M"
            window >= THOUSAND -> "${window / THOUSAND}K"
            else -> window.toString()
        }

    private const val MILLION = 1_000_000
    private const val THOUSAND = 1_000

    /**
     * [messages] cut to fit a [window]-token model with a quarter left for the reply: the system
     * prompt keeps at most half the budget, then the newest turns are kept and the oldest dropped.
     * The newest turn always stays, even when it alone is too long. When turns were dropped, the
     * kept ones start at a user turn.
     */
    fun fit(
        messages: List<ChatMessage>,
        window: Int,
    ): List<ChatMessage> {
        val budget = window - window / REPLY_SHARE
        val system = messages.takeWhile { it.role == "system" }.map { it.copy(text = cut(it.text, budget / 2)) }
        var used = system.sumOf { tokens(it.text) + TURN_OVERHEAD }
        val kept = ArrayDeque<ChatMessage>()
        for (message in messages.drop(system.size).asReversed()) {
            val cost = tokens(message.text) + TURN_OVERHEAD
            if (kept.isNotEmpty() && used + cost > budget) break
            kept.addFirst(message)
            used += cost
        }
        // A cut conversation must still open with the user's turn (some APIs reject anything else).
        val trimmed = kept.size < messages.size - system.size
        while (trimmed && kept.size > 1 && kept.first().role != "user") kept.removeFirst()
        return system + kept
    }

    /** [text] shortened to about [maxTokens] tokens (a proportional cut: good enough for a prompt). */
    private fun cut(
        text: String,
        maxTokens: Int,
    ): String {
        val have = tokens(text)
        return if (have <= maxTokens) text else text.take((text.length.toLong() * maxTokens / have).toInt())
    }

    private const val REPLY_SHARE = 4

    fun ratio(
        used: Int,
        window: Int,
    ): Float = if (window <= 0) 0f else (used.toFloat() / window).coerceIn(0f, 1f)

    fun level(ratio: Float): ContextLevel =
        when {
            ratio >= CRITICAL -> ContextLevel.CRITICAL
            ratio >= WARN -> ContextLevel.WARN
            else -> ContextLevel.OK
        }

    /** CJK ideographs, kana, hangul and full-width forms: roughly one token each. */
    private fun isWide(ch: Char): Boolean {
        val code = ch.code
        return code in CJK_START..CJK_END || code in HANGUL_START..HANGUL_END || code in FULLWIDTH_START..FULLWIDTH_END
    }

    private const val CJK_START = 0x2E80
    private const val CJK_END = 0x9FFF
    private const val HANGUL_START = 0xAC00
    private const val HANGUL_END = 0xD7AF
    private const val FULLWIDTH_START = 0xFF00
    private const val FULLWIDTH_END = 0xFFEF
}
