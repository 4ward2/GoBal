package com.danielealbano.androidremotecontrolmcp.services.vault

/** Splitting a spoken sentence into a slot name and its value, for names the lexicon does not know. */
object VoiceLabels {
    private val SPLIT_WORDS = listOf("is", "was", "equals", "hai", "है", "इज", "इस", ":", "=")
    private val SPLIT_CHARS = charArrayOf('是', '为', '叫', '：', ':')

    private val PHONE_WORDS = listOf("电话", "手机", "phone", "mobile", "फोन", "मोबाइल")
    private val ADDRESS_WORDS = listOf("地址", "住址", "address", "पता")
    private val EMAIL_WORDS = listOf("邮箱", "email", "e-mail", "ईमेल")
    private val AADHAAR_WORDS = listOf("aadhaar", "aadhar", "आधार")
    private val NUMBER_ENDINGS =
        listOf("号", "号码", "编号", "卡号", "number", "no", "id", "code", "नंबर", "नम्बर", "संख्या", "आईडी", "कोड")

    /** The value after a slot name: leading "是 / is / है" and trailing "है / 。" removed. */
    fun valuePart(rest: String): String =
        VoiceEdits.stripTrailing(VoiceEdits.stripLeading(rest, VoiceLexicon.SEPARATORS), VoiceLexicon.TRAILING_WORDS)

    /**
     * Splits at the first separator word ("是", "is", "है") that has something after it, otherwise
     * where the first number starts. Null when no name can be found before the value.
     */
    fun split(text: String): Pair<String, String>? {
        val bySeparator =
            separatorPositions(text)
                .filter { (start, end) -> start > 0 && valuePart(text.substring(end)).isNotEmpty() }
                .minByOrNull { it.first }
        val bounds = bySeparator ?: firstNumberStart(text)?.let { it to it }
        val label = bounds?.let { text.substring(0, it.first).trim() }.orEmpty()
        return if (bounds == null || label.isEmpty()) null else label to valuePart(text.substring(bounds.second))
    }

    /** What kind of value a slot name asks for, from the words in it. */
    fun inferKind(label: String): Pair<VoiceValueKind, VaultCategory> {
        fun has(words: List<String>) = words.any { VoiceText.containsPhrase(label, it) }
        val lastWord = VoiceText.foldAll(label.substringAfterLast(' '))
        val endsLikeNumber = NUMBER_ENDINGS.any { lastWord.endsWith(VoiceText.foldAll(it)) }
        return when {
            has(PHONE_WORDS) -> VoiceValueKind.PHONE to VaultCategory.CONTACT
            has(listOf("身份证")) -> VoiceValueKind.ID_CARD to VaultCategory.IDENTITY
            has(AADHAAR_WORDS) -> VoiceValueKind.AADHAAR to VaultCategory.IDENTITY
            has(ADDRESS_WORDS) -> VoiceValueKind.TEXT to VaultCategory.ADDRESS
            has(EMAIL_WORDS) -> VoiceValueKind.EMAIL to VaultCategory.CONTACT
            endsLikeNumber -> VoiceValueKind.NUMBER to VaultCategory.OTHER
            else -> VoiceValueKind.TEXT to VaultCategory.OTHER
        }
    }

    /** (start, end) of every separator: whole words for Latin / Hindi, single characters for Chinese. */
    private fun separatorPositions(text: String): List<Pair<Int, Int>> {
        val chars = text.indices.filter { text[it] in SPLIT_CHARS }.map { it to it + 1 }
        val words =
            wordStarts(text).flatMap { s ->
                SPLIT_WORDS.mapNotNull { w -> VoiceText.matchAt(text, s, w).takeIf { it > s }?.let { s to it } }
            }
        return chars + words
    }

    /** Start of the first number after a non-number: "一卡通号 一二三" splits before the second 一. */
    private fun firstNumberStart(text: String): Int? {
        val tokenStart =
            wordStarts(text).firstOrNull { s ->
                s > 0 && SpokenNumbers.isNumberWord(text.substring(s).substringBefore(' '))
            }
        val charStart = (1 until text.length).firstOrNull { i -> startsDigitsAt(text, i) }
        return listOfNotNull(tokenStart, charStart).minOrNull()
    }

    /** A digit right after a Chinese character or a space (not inside a Latin word like "b2b"). */
    private fun startsDigitsAt(
        text: String,
        i: Int,
    ): Boolean {
        val here = text[i]
        val before = text[i - 1]
        val isDigitHere = (ChineseNumerals.isPlainDigit(here) && VoiceText.isCjk(here)) || here in '0'..'9'
        val afterNonDigit = !ChineseNumerals.isPlainDigit(before) && before !in '0'..'9'
        return isDigitHere && afterNonDigit && (VoiceText.isCjk(before) || before == ' ')
    }

    private fun wordStarts(text: String): List<Int> = text.indices.filter { it == 0 || text[it - 1] == ' ' }
}
