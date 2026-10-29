package com.danielealbano.androidremotecontrolmcp.services.assist

import kotlin.math.abs

/**
 * Extracts a one-time verification code from an SMS / notification text.
 *
 * A text only qualifies when it contains a verification keyword; among the 4–8 character codes
 * in it, the one closest to a keyword wins. Digits that are clearly something else are skipped:
 * validity periods ("5分钟"), amounts ("20元"), dates, and masked card/phone tails ("尾号1234").
 */
object OtpExtractor {
    private const val PREFERRED_LENGTH = 6
    private const val CONTEXT_LOOKBEHIND = 3
    private const val TAIL_BEFORE_WINDOW = 12

    private val KEYWORD =
        Regex(
            "验证码|驗證碼|校验码|校驗碼|动态码|動態碼|动态密码|短信码|确认码|確認碼|安全码|认证码|登录码|提取码|" +
                "verification code|security code|one[- ]time|passcode|\\botp\\b|\\bcode\\b",
            RegexOption.IGNORE_CASE,
        )

    /** Pure digits, or letters+digits mixed (e.g. "A3F9K2"); never a longer alphanumeric run. */
    private val CANDIDATE =
        Regex(
            "(?<![0-9A-Za-z])" +
                "(?:[0-9]{4,8}|(?=[A-Za-z]*[0-9])(?=[0-9]*[A-Za-z])[0-9A-Za-z]{4,8})" +
                "(?![0-9A-Za-z])",
        )

    private val UNIT_AFTER =
        Regex(
            "^\\s*(?:分钟|分鐘|秒|小时|小時|天|元|块|年|月|日|号|min|mins|minutes|s\\b|%)",
            RegexOption.IGNORE_CASE,
        )

    private val TAIL_BEFORE = Regex("(?:尾号|尾號|末四位|后四位|ending in|ends with)\\s*$", RegexOption.IGNORE_CASE)

    /** Nearest to a keyword first; among equals, the length closest to the usual 6 digits. */
    private val BEST_CANDIDATE =
        compareBy<Pair<String, Int>> { it.second }.thenBy { abs(it.first.length - PREFERRED_LENGTH) }

    /** Returns the verification code in [text], or null when there is none. */
    fun extract(text: String?): String? {
        val keywords = text?.let { KEYWORD.findAll(it).map { match -> match.range }.toList() }.orEmpty()
        if (text == null || keywords.isEmpty()) return null
        return CANDIDATE
            .findAll(text)
            .filter { isPlausible(text, it.range) }
            .map { match -> match.value to distanceToKeyword(match.range, keywords) }
            .minWithOrNull(BEST_CANDIDATE)
            ?.first
    }

    private fun isPlausible(
        text: String,
        range: IntRange,
    ): Boolean {
        val after = text.substring(range.last + 1)
        val before = text.substring((range.first - TAIL_BEFORE_WINDOW).coerceAtLeast(0), range.first)
        return !UNIT_AFTER.containsMatchIn(after) &&
            !TAIL_BEFORE.containsMatchIn(before) &&
            !isDateOrAmount(text, range)
    }

    private fun isDateOrAmount(
        text: String,
        range: IntRange,
    ): Boolean {
        val prev = text.getOrNull(range.first - 1)
        val next = text.getOrNull(range.last + 1)
        val prevBefore = (range.first - CONTEXT_LOOKBEHIND).coerceAtLeast(0)
        val dateSeparators = setOf('-', '/', ':', '.')
        return (prev in dateSeparators && text.getOrNull(range.first - 2)?.isDigit() == true) ||
            (next in dateSeparators && text.getOrNull(range.last + 2)?.isDigit() == true) ||
            text.substring(prevBefore, range.first).contains('¥')
    }

    private fun distanceToKeyword(
        range: IntRange,
        keywords: List<IntRange>,
    ): Int =
        keywords.minOf { keyword ->
            when {
                range.first > keyword.last -> range.first - keyword.last
                keyword.first > range.last -> keyword.first - range.last
                else -> 0
            }
        }
}
