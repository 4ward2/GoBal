package com.danielealbano.androidremotecontrolmcp.services.vault

/**
 * Converts spoken Chinese numerals, as an on-device recognizer writes them, into ASCII digits.
 *
 * Numbers are usually read digit by digit ("一三八" → 138). Colloquial and radio readings are
 * understood too: 幺 for 1 (and the recognizer's homophones 腰 妖 邀), 两 for 2, 洞 for 0, 拐 for 7,
 * 勾 for 9, and "三个八" for 888. A run that contains a unit (十 百 千 万) is read as one number
 * instead ("一百零五" → 105, "三千五" → 3500).
 */
object ChineseNumerals {
    /** Every way of writing each digit, indexed by its value. */
    private val DIGITS: Map<Char, Int> =
        listOf("0零〇洞", "1一幺壹腰妖邀", "2二两贰", "3三叁", "4四肆", "5五伍", "6六陆", "7七柒拐", "8八捌", "9九玖勾")
            .flatMapIndexed { value, spellings -> spellings.map { it to value } }
            .toMap()

    /** Digits that never mean anything else; used to find where a label ends and a number starts. */
    private const val PLAIN_DIGITS = "0123456789零〇一二三四五六七八九两幺"

    /** "三个八" → "八八八": a count of 2..9 followed by 个 and one digit. */
    private val REPEAT = Regex("([二两三四五六七八九]) ?个 ?([零〇洞一幺二两三四五六七八九拐勾腰妖邀])")

    private const val TEN = 10L
    private const val HUNDRED = 100L
    private const val THOUSAND = 1_000L
    private const val TEN_THOUSAND = 10_000L

    private val UNITS: Map<Char, Long> =
        mapOf('十' to TEN, '拾' to TEN, '百' to HUNDRED, '佰' to HUNDRED, '千' to THOUSAND, '仟' to THOUSAND)

    private const val WAN = '万'

    fun isDigit(c: Char): Boolean = c in DIGITS

    fun isPlainDigit(c: Char): Boolean = c in PLAIN_DIGITS

    fun isNumeral(c: Char): Boolean = c in DIGITS || c in UNITS || c == WAN

    /** "三个八" (also "三 个 八") → "八八八". */
    fun expandRepeats(text: String): String = REPEAT.replace(text, ::repeatDigit)

    private fun repeatDigit(m: MatchResult): String {
        val (count, digit) = m.destructured
        return digit.repeat(DIGITS.getValue(count[0]))
    }

    /**
     * Replaces every numeral run in [input] by its digits and leaves everything else as it is.
     *
     * @return null when a run with units cannot be read as one number (for example "二零二一百"),
     *   which means the speaker mixed both styles and should read the number digit by digit.
     */
    fun convert(input: String): String? {
        val text = expandRepeats(input)
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (!isNumeral(text[i])) {
                out.append(text[i])
                i++
                continue
            }
            var end = i
            while (end < text.length && isNumeral(text[end])) end++
            out.append(convertRun(text.substring(i, end)) ?: return null)
            i = end
        }
        return out.toString()
    }

    private fun convertRun(run: String): String? =
        if (run.none { it in UNITS || it == WAN }) {
            run.map { DIGITS.getValue(it) }.joinToString("")
        } else {
            parseWithUnits(run)?.toString()
        }

    /** Reads a numeral with units; handles the colloquial "三千五" (3500) and "一万二" (12000). */
    internal fun parseWithUnits(run: String): Long? {
        val acc = UnitAccumulator()
        val ok =
            run.all { c ->
                when (c) {
                    in DIGITS -> acc.digit(DIGITS.getValue(c).toLong())
                    in UNITS -> acc.unit(UNITS.getValue(c))
                    else -> acc.wan()
                }
            }
        return if (ok) acc.result() else null
    }

    /** State of a left-to-right read; each step returns false when the numeral is malformed. */
    private class UnitAccumulator {
        private var total = 0L
        private var section = 0L
        private var pending = NONE
        private var lastUnit = 0L
        private var zeroSinceUnit = false

        fun digit(d: Long): Boolean {
            if (pending != NONE) return false
            if (d == 0L) zeroSinceUnit = true else pending = d
            return true
        }

        /** A bare 十 means 10 at the start ("十五") and after 零 ("一千零十"); other units need a digit. */
        fun unit(unit: Long): Boolean {
            val count =
                when {
                    pending != NONE -> pending
                    unit == TEN && (lastUnit == 0L || zeroSinceUnit) -> 1L
                    else -> NONE
                }
            // Units must get smaller left to right: "十百" and "二十三十" are not numbers.
            val ok = count != NONE && lastUnit !in 1..unit
            if (ok) {
                section += count * unit
                pending = NONE
                lastUnit = unit
                zeroSinceUnit = false
            }
            return ok
        }

        fun wan(): Boolean {
            val value = section + pending.coerceAtLeast(0)
            if (value == 0L) return false
            total = (total + value) * TEN_THOUSAND
            section = 0
            pending = NONE
            lastUnit = TEN_THOUSAND
            zeroSinceUnit = false
            return true
        }

        fun result(): Long {
            val tail =
                when {
                    pending == NONE -> 0L
                    lastUnit > 1 && !zeroSinceUnit -> pending * lastUnit / TEN
                    else -> pending
                }
            return total + section + tail
        }
    }

    private const val NONE = -1L
}
