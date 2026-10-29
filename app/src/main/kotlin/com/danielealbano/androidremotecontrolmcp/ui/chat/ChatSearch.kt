package com.danielealbano.androidremotecontrolmcp.ui.chat

/** Finding a word in the conversation on screen. Pure. */
object ChatSearch {
    /** Where [query] occurs in [text], ignoring case; occurrences never overlap. */
    fun ranges(
        text: String,
        query: String,
    ): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        var from = text.indexOf(query, ignoreCase = true)
        while (from >= 0) {
            out += from until from + query.length
            from = text.indexOf(query, from + query.length, ignoreCase = true)
        }
        return out
    }

    /** The ids of the rows showing [query], oldest first: in their text or in an attachment's name. */
    fun hits(
        items: List<ChatItem>,
        query: String,
    ): List<String> {
        val wanted = query.trim()
        if (wanted.isEmpty()) return emptyList()
        return items
            .filter { item ->
                val name = attachmentOf(item)?.name.orEmpty()
                textOf(item).contains(wanted, ignoreCase = true) || name.contains(wanted, ignoreCase = true)
            }.map { it.messageId }
    }

    /** The hit after ([forward]) or before [current] among [count], wrapping around at either end. */
    fun step(
        current: Int,
        count: Int,
        forward: Boolean,
    ): Int =
        when {
            count <= 0 -> 0
            forward -> (current + 1) % count
            else -> (current - 1 + count) % count
        }
}
