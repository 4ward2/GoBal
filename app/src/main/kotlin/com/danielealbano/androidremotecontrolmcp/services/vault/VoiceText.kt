package com.danielealbano.androidremotecontrolmcp.services.vault

/**
 * Text helpers shared by the voice parser. Recognizers space out Chinese words, write Hindi with or
 * without the nukta dot (फ़ोन / फोन) and with chandrabindu or anusvara (पाँच / पांच); matching folds
 * all of that away while values keep what was said.
 */
object VoiceText {
    private const val NUKTA = 0x093C
    private const val CHANDRABINDU = 0x0901
    private const val ANUSVARA = 0x0902
    private const val FULL_WIDTH_OFFSET = 0xFEE0
    private const val FULL_WIDTH_FIRST = 0xFF01
    private const val FULL_WIDTH_LAST = 0xFF5E
    private const val DEVANAGARI_ZERO = 0x0966
    private const val DEVANAGARI_NINE = 0x096F
    private const val CJK_FIRST = 0x4E00
    private const val CJK_LAST = 0x9FFF
    private const val CJK_EXT_A_FIRST = 0x3400
    private const val CJK_EXT_A_LAST = 0x4DBF

    /** Devanagari vowel signs and marks that continue a word (candrabindu … virama and friends). */
    private const val SIGNS_A_FIRST = 0x0900
    private const val SIGNS_A_LAST = 0x0903
    private const val SIGNS_B_FIRST = 0x093A
    private const val SIGNS_B_LAST = 0x094F
    private val SIGNS_A = SIGNS_A_FIRST..SIGNS_A_LAST
    private val SIGNS_B = SIGNS_B_FIRST..SIGNS_B_LAST

    /** Full-width to ASCII, Devanagari digits to ASCII, ASCII lower case, single spaces. */
    fun clean(raw: String): String {
        val out = StringBuilder(raw.length)
        for (c in raw) {
            val mapped =
                when {
                    c.isWhitespace() -> ' '
                    c.code in FULL_WIDTH_FIRST..FULL_WIDTH_LAST -> c - FULL_WIDTH_OFFSET
                    c.code in DEVANAGARI_ZERO..DEVANAGARI_NINE -> '0' + (c.code - DEVANAGARI_ZERO)
                    else -> c
                }.let { if (it in 'A'..'Z') it.lowercaseChar() else it }
            if (mapped != ' ' || (out.isNotEmpty() && out.last() != ' ')) out.append(mapped)
        }
        return out.toString().trim()
    }

    /** The character used for comparisons, or null for characters comparisons skip. */
    fun fold(c: Char): Char? =
        when (c.code) {
            ' '.code, NUKTA -> null
            CHANDRABINDU -> ANUSVARA.toChar()
            else -> c
        }

    fun foldAll(text: String): String = text.mapNotNull(::fold).joinToString("")

    fun isCjk(c: Char): Boolean = c.code in CJK_FIRST..CJK_LAST || c.code in CJK_EXT_A_FIRST..CJK_EXT_A_LAST

    /** Part of a non-Chinese word: a letter or a Devanagari vowel sign / mark. */
    fun isWordChar(c: Char): Boolean = (c.isLetter() && !isCjk(c)) || isDevanagariSign(c)

    private fun isDevanagariSign(c: Char): Boolean = c.code in SIGNS_A || c.code in SIGNS_B

    /**
     * Matches [phrase] in [text] at [from], ignoring spaces and folded marks.
     *
     * @return the index in [text] just after the match, or -1. Non-Chinese phrases must end at a
     *   word boundary, so "pan" does not match the start of "pancake".
     */
    fun matchAt(
        text: String,
        from: Int,
        phrase: String,
    ): Int {
        val key = foldAll(phrase)
        var i = from
        var matched = key.isNotEmpty()
        for (k in key) {
            while (i < text.length && fold(text[i]) == null) i++
            if (i >= text.length || fold(text[i]) != k) {
                matched = false
                break
            }
            i++
        }
        while (matched && i < text.length && text[i].code == NUKTA) i++
        val atBoundary = matched && (isCjk(key.last()) || i >= text.length || !isWordChar(text[i]))
        return if (atBoundary) i else -1
    }

    /** Whether [phrase] occurs in [text]: Chinese anywhere, other scripts only as whole words. */
    fun containsPhrase(
        text: String,
        phrase: String,
    ): Boolean {
        val chinese = phrase.firstOrNull()?.let(::isCjk) == true
        return text.indices.any { s ->
            (chinese || s == 0 || !isWordChar(text[s - 1])) && matchAt(text, s, phrase) >= 0
        }
    }
}
