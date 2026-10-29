package com.danielealbano.androidremotecontrolmcp.services.vault

/** Rewrites device-derived text before it is returned to the model. */
fun interface ContentRedactor {
    fun redact(text: String): String

    companion object {
        val NONE = ContentRedactor { it }
    }
}

/**
 * Replaces every occurrence of a vault value with `‹vault:<slot id>›`.
 *
 * Apps reformat what they show, so matching is loose where that is safe:
 *  - number-like values (phones, ID numbers ending in X, card numbers) match with a short run of
 *    spaces, dashes, dots, brackets or full-width spaces between characters, and a stored
 *    `+86` / `86` country prefix is optional on screen;
 *  - text values match any whitespace run in place of whitespace, and also their JSON-escaped
 *    form (tool output is often JSON, where a newline becomes a backslash-n).
 *
 * Latin values shorter than [MIN_LENGTH] are skipped (redacting "Tom" everywhere would wreck the
 * screen text); values containing Chinese characters need only [MIN_CJK_LENGTH], so two- and
 * three-character names are still covered.
 */
class VaultRedactor(
    values: Map<String, String>,
) : ContentRedactor {
    private val patterns: List<Pair<Regex, String>> =
        values.entries
            .mapNotNull { (id, raw) ->
                val value = raw.trim()
                if (value.length < minLength(value)) null else Triple(id, value, patternFor(value))
            }.sortedByDescending { it.second.length }
            .map { (id, _, regex) -> regex to "‹vault:$id›" }

    override fun redact(text: String): String {
        if (patterns.isEmpty() || text.isEmpty()) return text
        var out = text
        for ((regex, replacement) in patterns) {
            out = regex.replace(out, replacement)
        }
        return out
    }

    companion object {
        const val MIN_LENGTH = 4
        const val MIN_CJK_LENGTH = 2
        private const val MIN_DIGITS_FOR_LOOSE_MATCH = 6
        private const val CN_PREFIX = "86"
        private const val CN_MOBILE_DIGITS = 11
        private const val SEPARATORS = "[\\s\\u00a0\\u3000\\-.()（）]{0,2}"
        private val CJK = Regex("[\\u4e00-\\u9fff]")
        private val NUMBER_LIKE = Regex("\\+?[0-9][0-9 \\-]*[0-9Xx]")

        private fun minLength(value: String) = if (CJK.containsMatchIn(value)) MIN_CJK_LENGTH else MIN_LENGTH

        private fun patternFor(value: String): Regex {
            val chars = value.filter { it.isDigit() || it == 'X' || it == 'x' }
            return if (NUMBER_LIKE.matches(value) && chars.count(Char::isDigit) >= MIN_DIGITS_FOR_LOOSE_MATCH) {
                numberPattern(chars)
            } else {
                textPattern(value)
            }
        }

        /** Digits (and a final X) separated by optional formatting; never inside a longer number. */
        private fun numberPattern(chars: String): Regex {
            val forms = mutableListOf(chars)
            if (chars.startsWith(CN_PREFIX) && chars.length == CN_PREFIX.length + CN_MOBILE_DIGITS) {
                forms += chars.removePrefix(CN_PREFIX)
            }
            val alternatives =
                forms.joinToString("|") { form ->
                    form.map { c -> if (c == 'X' || c == 'x') "[Xx]" else c.toString() }.joinToString(SEPARATORS)
                }
            return Regex("(?<![0-9])(?:\\+?$CN_PREFIX$SEPARATORS)?(?:$alternatives)(?![0-9Xx])")
        }

        /** Literal text where whitespace is flexible, plus its JSON-escaped spelling. */
        private fun textPattern(value: String): Regex {
            val jsonEscaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            val alternatives = listOf(value, jsonEscaped).distinct().joinToString("|") { flexibleWhitespace(it) }
            return Regex(alternatives, RegexOption.IGNORE_CASE)
        }

        private fun flexibleWhitespace(text: String): String =
            text
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .joinToString("\\s+") { Regex.escape(it) }
    }
}
