package com.danielealbano.androidremotecontrolmcp.services.call

/**
 * Cuts a streaming reply into sentences as soon as they are complete, so the first sentence can
 * be synthesised while the model is still writing the rest. Markdown markers and the character's
 * `<status>` tag are stripped: a voice should not read out asterisks or mood tags.
 */
class SentenceSplitter {
    private val buffer = StringBuilder()

    /** Feeds a chunk of streamed text; returns the sentences that became complete. */
    fun feed(chunk: String): List<String> {
        buffer.append(chunk)
        dropStatusTags()
        val out = mutableListOf<String>()
        var cut = 0
        // A status tag may still be arriving piecemeal: never cut inside it.
        val hold = buffer.indexOf(STATUS_OPEN).let { if (it < 0) buffer.length else it }
        for (i in 0 until hold) {
            val ch = buffer[i]
            val boundary = ch in TERMINATORS || (ch == '\n' && i > cut)
            if (boundary) {
                val sentence = clean(buffer.substring(cut, i + 1))
                if (sentence.length >= MIN_CHARS) {
                    out += sentence
                    cut = i + 1
                }
            }
        }
        if (cut > 0) buffer.delete(0, cut)
        return out
    }

    /** Whatever is left when the stream ends. */
    fun finish(): List<String> {
        dropStatusTags()
        val rest = clean(buffer.toString())
        buffer.setLength(0)
        return if (rest.isNotEmpty()) listOf(rest) else emptyList()
    }

    /** Removes every complete status tag from the buffer; a partial one at the end is kept for later. */
    private fun dropStatusTags() {
        val cleaned = STATUS_TAG.replace(buffer, "")
        if (cleaned.length != buffer.length) {
            buffer.setLength(0)
            buffer.append(cleaned)
        }
    }

    private fun clean(text: String): String = MARKERS.replace(text, "").replace('\n', ' ').trim()

    private companion object {
        val TERMINATORS = setOf('。', '！', '？', '；', '.', '!', '?', ';', '…')
        val MARKERS = Regex("[*#`>_~]+|\\[[^\\]]*]\\([^)]*\\)")
        val STATUS_TAG = Regex("<status>.*?</status>", RegexOption.IGNORE_CASE)
        const val STATUS_OPEN = "<status"
        const val MIN_CHARS = 2
    }
}
