package com.danielealbano.androidremotecontrolmcp.services.call

/** A stretch of text the phone played, with when it was audible (milliseconds on the call clock). */
data class SpokenSpan(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/** A recognised utterance with when it was heard (milliseconds on the call clock). */
data class HeardSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Tells the phone's own voice (picked up by the microphone) from the user's, using the two things
 * only we know: what text was playing at each moment, and the acoustic delay between playing and
 * hearing. An utterance is an echo when it overlaps a spoken span in time (shifted by the delay)
 * and repeats its characters almost verbatim. Short utterances are never judged by text alone.
 */
class EchoFilter(
    private val delayMs: Long = DEFAULT_DELAY_MS,
    private val slackMs: Long = DEFAULT_SLACK_MS,
) {
    /** True when [heard] is the phone hearing itself say one of [spoken]. */
    fun isEcho(
        heard: HeardSegment,
        spoken: List<SpokenSpan>,
    ): Boolean {
        val text = normalize(heard.text)
        if (text.length < MIN_CHARS) return false
        val windowStart = heard.startMs - delayMs - slackMs
        val windowEnd = heard.endMs - delayMs + slackMs
        val overlapping = spoken.filter { it.endMs >= windowStart && it.startMs <= windowEnd }
        if (overlapping.isEmpty()) return false
        val played = normalize(overlapping.joinToString("") { it.text })
        return similarity(text, played) >= MIN_SIMILARITY
    }

    /** Share of [heard]'s characters that appear, in order, in [played]. */
    fun similarity(
        heard: String,
        played: String,
    ): Float {
        if (heard.isEmpty() || played.isEmpty()) return 0f
        return lcs(heard, played).toFloat() / heard.length
    }

    private fun lcs(
        a: String,
        b: String,
    ): Int {
        var prev = IntArray(b.length + 1)
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    companion object {
        const val DEFAULT_DELAY_MS = 150L
        const val DEFAULT_SLACK_MS = 500L
        const val MIN_CHARS = 4
        const val MIN_SIMILARITY = 0.7f

        /** Letters and digits only, lower-cased: recognisers drop punctuation and add spaces. */
        fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
    }
}
