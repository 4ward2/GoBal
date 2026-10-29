package com.danielealbano.androidremotecontrolmcp.services.vault

/** Trimming and tidying of recognizer text; comparisons go through [VoiceText]. */
object VoiceEdits {
    /** Removes any of [phrases] from the start of [text], repeatedly ("帮我 记住 我的 …"). */
    fun stripLeading(
        text: String,
        phrases: List<String>,
    ): String {
        var current = text.trim()
        while (true) {
            val end = phrases.map { VoiceText.matchAt(current, 0, it) }.filter { it > 0 }.maxOrNull() ?: return current
            current = current.substring(end).trim()
        }
    }

    /** Removes trailing [words] and sentence punctuation ("… है", "…。"). */
    fun stripTrailing(
        text: String,
        words: List<String>,
    ): String {
        var current = text.trim()
        while (true) {
            val word = words.firstOrNull { current.endsWith(it) && isWholeTail(current, it) } ?: return current
            current = current.removeSuffix(word).trim()
        }
    }

    /** Punctuation and Chinese may end anywhere; a Latin or Hindi word must follow a space. */
    private fun isWholeTail(
        text: String,
        word: String,
    ): Boolean {
        val start = text.length - word.length
        val first = word.first()
        val attachesAnywhere = !first.isLetter() || VoiceText.isCjk(first)
        return attachesAnywhere || start == 0 || text[start - 1] == ' '
    }

    /** Drops the recognizer's spaces next to Chinese characters; keeps them between other words. */
    fun tidySpaces(text: String): String {
        fun cjkAt(i: Int) = text.getOrNull(i)?.let(VoiceText::isCjk) == true
        return text.filterIndexed { i, c -> c != ' ' || !(cjkAt(i - 1) || cjkAt(i + 1)) }.trim()
    }

    /** "john smith" → "John Smith"; words with any other script or case are left alone. */
    fun capitalizeLatinWords(text: String): String =
        text.split(' ').joinToString(" ") { word ->
            if (word.isNotEmpty() && word.all(::isLowerLatin)) word.replaceFirstChar { it.uppercaseChar() } else word
        }

    private fun isLowerLatin(c: Char): Boolean = c in 'a'..'z' || c == '\'' || c == '-'
}
