package com.danielealbano.androidremotecontrolmcp.services.vault

/**
 * Turns spoken numbers in a recognizer transcript into ASCII digits, word by word, for Chinese,
 * English (including Indian English) and Hindi (Devanagari and romanized).
 *
 * Only used on the value part of a number-like entry, so homophones the recognizers actually
 * produce ("to" for two, "for" for four, "ate" for eight) are safe to read as digits here.
 * Repeaters work in every language: "double seven" → 77, "triple zero" → 000, "डबल नौ" → 99,
 * and Chinese "三个八" → 888.
 */
object SpokenNumbers {
    /** Spoken forms of 0..9, indexed by value: English, recognizer homophones, Hinglish, Hindi. */
    private val DIGIT_WORDS: Map<String, Int> =
        listOf(
            listOf("zero", "oh", "o", "nought", "nil", "shunya", "शून्य", "जीरो", "ज़ीरो"),
            listOf("one", "won", "ek", "एक", "वन"),
            listOf("two", "to", "too", "do", "दो", "टू"),
            listOf("three", "tree", "teen", "तीन", "थ्री"),
            listOf("four", "for", "fore", "char", "chaar", "चार", "फोर"),
            listOf("five", "paanch", "panch", "पांच", "पाँच", "फाइव"),
            listOf("six", "chhe", "chhah", "छह", "छः", "छे", "सिक्स"),
            listOf("seven", "saat", "सात", "सेवन"),
            listOf("eight", "ate", "aath", "आठ", "एट"),
            listOf("nine", "nau", "नौ", "नाइन"),
        ).indexed(0)

    /** Digit words that are also ordinary words; never taken as the start of a number in a label. */
    private val AMBIGUOUS_WORDS: Set<String> =
        setOf("oh", "o", "to", "too", "do", "for", "fore", "won", "ate", "tree", "teen", "char", "chaar", "वन", "एट")
            .map(VoiceText::foldAll)
            .toSet()

    private const val TEN = 10

    /** 10..19; Hindi has its own word for every number, only 10..12 are common enough to list. */
    private val TEEN_WORDS: Map<String, Int> =
        listOf(
            listOf("ten", "दस"),
            listOf("eleven", "ग्यारह"),
            listOf("twelve", "बारह"),
            listOf("thirteen"),
            listOf("fourteen"),
            listOf("fifteen"),
            listOf("sixteen"),
            listOf("seventeen"),
            listOf("eighteen"),
            listOf("nineteen"),
        ).indexed(TEN)

    /** 20, 30, … 90. */
    private val TENS_WORDS: Map<String, Int> =
        listOf(
            listOf("twenty", "बीस"),
            listOf("thirty", "तीस"),
            listOf("forty", "चालीस"),
            listOf("fifty", "पचास"),
            listOf("sixty", "साठ"),
            listOf("seventy", "सत्तर"),
            listOf("eighty", "अस्सी"),
            listOf("ninety", "नब्बे"),
        ).indexed(2).mapValues { it.value * TEN }

    /** "double" repeats the next digit twice, "triple" three times. */
    private val REPEATERS: Map<String, Int> =
        listOf(listOf("double", "डबल", "dabal"), listOf("triple", "ट्रिपल")).indexed(2)

    private val HUNDRED_WORDS = setOf("hundred", "सौ", "sau").map(VoiceText::foldAll).toSet()

    /** "oh" and "o" are only a zero next to other digits; alone they are a word or a letter. */
    private val ZERO_ONLY_BETWEEN_DIGITS = setOf("oh", "o")

    private const val MAX_DIGIT = 9

    /** Word lists indexed from [first], keys folded for matching. */
    private fun List<List<String>>.indexed(first: Int): Map<String, Int> =
        flatMapIndexed { i, words -> words.map { VoiceText.foldAll(it) to first + i } }.toMap()

    /** A word that can only be a number: where a spoken label ends and its value starts. */
    fun isNumberWord(token: String): Boolean {
        val key = VoiceText.foldAll(token)
        return key !in AMBIGUOUS_WORDS && startsNumber(key)
    }

    /**
     * @return the transcript with number words replaced by digits (adjacent numbers joined, other
     *   words kept with single spaces), or null when a Chinese numeral with units is malformed.
     */
    fun convert(spaced: String): String? {
        val tokens = ChineseNumerals.expandRepeats(spaced).split(' ').filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var digitRun = StringBuilder()
        var i = 0

        fun flush() {
            if (digitRun.isNotEmpty()) out.add(digitRun.toString())
            digitRun = StringBuilder()
        }
        while (i < tokens.size) {
            val step = readNumber(tokens, i, digitRun.isNotEmpty())
            if (step == null) {
                val chinese = ChineseNumerals.convert(tokens[i]) ?: return null
                if (chinese.all { it.isDigit() }) {
                    digitRun.append(chinese)
                } else {
                    flush()
                    out.add(chinese)
                }
                i++
            } else {
                digitRun.append(step.digits)
                i += step.consumed
            }
        }
        flush()
        return out.joinToString(" ")
    }

    private data class Step(
        val digits: String,
        val consumed: Int,
    )

    private fun readNumber(
        tokens: List<String>,
        at: Int,
        afterDigit: Boolean,
    ): Step? {
        val word = VoiceText.foldAll(tokens[at])
        val next = tokens.getOrNull(at + 1)?.let(VoiceText::foldAll)
        return when {
            word in REPEATERS && next != null -> repeated(REPEATERS.getValue(word), next)
            word in HUNDRED_WORDS -> Step(if (afterDigit) "00" else "100", 1)
            word in TENS_WORDS -> tens(TENS_WORDS.getValue(word), next)
            word in TEEN_WORDS -> Step(TEEN_WORDS.getValue(word).toString(), 1)
            word in ZERO_ONLY_BETWEEN_DIGITS && !afterDigit && (next == null || !startsNumber(next)) -> null
            word in DIGIT_WORDS -> Step(DIGIT_WORDS.getValue(word).toString(), 1)
            else -> null
        }
    }

    /** "double seven" and the recognizer's plural "double sevens". */
    private fun repeated(
        times: Int,
        next: String,
    ): Step? {
        val digit = DIGIT_WORDS[next] ?: DIGIT_WORDS[next.removeSuffix("s")] ?: next.singleDigitOrNull()
        return digit?.let { Step(it.toString().repeat(times), 2) }
    }

    /** "ninety eight" → 98, "ninety" → 90. */
    private fun tens(
        value: Int,
        next: String?,
    ): Step {
        val unit = next?.let { DIGIT_WORDS[it] }?.takeIf { it in 1..MAX_DIGIT }
        return if (unit != null) Step((value + unit).toString(), 2) else Step(value.toString(), 1)
    }

    private fun startsNumber(key: String): Boolean =
        key in DIGIT_WORDS ||
            key in TEEN_WORDS ||
            key in TENS_WORDS ||
            key in REPEATERS ||
            key.firstOrNull()?.let { it in '0'..'9' || ChineseNumerals.isPlainDigit(it) } == true

    private fun String.singleDigitOrNull(): Int? = singleOrNull()?.takeIf { it in '0'..'9' }?.let { it - '0' }
}
