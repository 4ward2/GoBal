package com.danielealbano.androidremotecontrolmcp.services.im.render

/**
 * Cuts a plain-text reply into pieces that fit a channel's character limit, preferring to cut at
 * a paragraph break, then a sentence end or a bare line break (so code without punctuation still
 * cuts at a line), then any whitespace, and only hard-cutting a word as a last resort — never
 * inside an emoji's surrogate pair. The caller is expected to merge adjacent text before calling
 * this, since cutting here only ever shortens, never joins.
 *
 * The "（i/N）" suffix is only added once a reply needed more than one piece, and the budget is
 * re-split a few times to leave room for it. When maxChars is smaller than the suffix a long
 * reply's piece count needs (for example a three-digit count needs "（123/123）", 9 characters), no
 * numbering scheme can keep every piece within maxChars; splitting still terminates and never
 * drops or duplicates a character, it just no longer guarantees the per-piece limit in that case.
 */
internal object TextChunker {
    private val BOUNDARY_CHARS = setOf('。', '！', '？', '.', '!', '?', '\n')
    private val WHITESPACE_CHARS = setOf(' ', '\t')
    private const val PARAGRAPH_BREAK = "\n\n"
    private const val MAX_RENUMBER_PASSES = 4

    /** [text] split to fit [maxChars]; pieces get a "（1/3）" suffix only when there is more than one. */
    fun chunk(
        text: String,
        maxChars: Int,
    ): List<String> {
        if (text.isEmpty()) return emptyList()
        return numbered(text, maxChars.coerceAtLeast(1))
    }

    private fun numbered(
        text: String,
        maxChars: Int,
    ): List<String> {
        var pieces = splitToBudget(text, maxChars)
        var budget = maxChars
        var pass = 0
        while (pieces.size > 1 && pass < MAX_RENUMBER_PASSES) {
            val reserved = maxOf(suffix(1, pieces.size).length, suffix(pieces.size, pieces.size).length)
            val next = (maxChars - reserved).coerceAtLeast(1)
            if (next == budget) break
            budget = next
            pieces = splitToBudget(text, budget)
            pass++
        }
        if (pieces.size <= 1) return pieces
        return pieces.mapIndexed { index, piece -> piece + suffix(index + 1, pieces.size) }
    }

    private fun splitToBudget(
        text: String,
        budget: Int,
    ): List<String> {
        val pieces = mutableListOf<String>()
        var remaining = text
        while (remaining.length > budget) {
            val cut = bestCut(remaining, budget)
            pieces += remaining.substring(0, cut)
            remaining = remaining.substring(cut)
        }
        pieces += remaining
        return pieces
    }

    private fun bestCut(
        text: String,
        budget: Int,
    ): Int {
        val limit = minOf(budget, text.length)
        return paragraphCut(text, limit)
            ?: boundaryCut(text, limit)
            ?: whitespaceCut(text, limit)
            ?: hardCut(text, limit)
    }

    private fun paragraphCut(
        text: String,
        limit: Int,
    ): Int? {
        var cut: Int? = null
        var from = 0
        while (true) {
            val found = text.indexOf(PARAGRAPH_BREAK, from)
            if (found < 0 || found + PARAGRAPH_BREAK.length > limit) break
            cut = found + PARAGRAPH_BREAK.length
            from = found + 1
        }
        return cut
    }

    private fun boundaryCut(
        text: String,
        limit: Int,
    ): Int? {
        for (i in minOf(limit, text.length) - 1 downTo 0) {
            if (text[i] in BOUNDARY_CHARS) return i + 1
        }
        return null
    }

    private fun whitespaceCut(
        text: String,
        limit: Int,
    ): Int? {
        for (i in minOf(limit, text.length) - 1 downTo 0) {
            if (text[i] in WHITESPACE_CHARS) return i + 1
        }
        return null
    }

    private fun hardCut(
        text: String,
        limit: Int,
    ): Int {
        val cut = minOf(limit, text.length)
        val splitsAPair = cut in 1 until text.length && text[cut - 1].isHighSurrogate() && text[cut].isLowSurrogate()
        if (!splitsAPair) return maxOf(cut, 1)
        // Back off to keep the pair in this piece; when the pair sits right at the start (cut == 1)
        // backing off would produce an empty piece, so the pair is let through whole instead — a
        // budget of 1 cannot honor both "make progress" and "never split a surrogate pair".
        return if (cut > 1) cut - 1 else cut + 1
    }

    private fun suffix(
        index: Int,
        total: Int,
    ): String = "（$index/$total）"
}
