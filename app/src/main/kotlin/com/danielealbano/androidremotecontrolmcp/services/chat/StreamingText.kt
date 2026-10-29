package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.services.character.StatusTag

/** What to show of a reply that is still arriving. */
object StreamingText {
    private val TAGS = listOf("<status>", PhoneHandoff.OPEN)
    private val OPEN_TAG = Regex("<status>[^<]*$", RegexOption.IGNORE_CASE)

    /**
     * [partial] without the character's `<status>` tag, including one that is only half written:
     * the tag comes last in a reply, so its first letters would otherwise flash by in the bubble.
     * A reply handing a phone task over shows nothing from its tag on: it becomes a task card.
     */
    fun visible(partial: String): String {
        val closed = StatusTag.split(partial).text
        val handOver = closed.indexOf(PhoneHandoff.OPEN, ignoreCase = true)
        val open = OPEN_TAG.replace(if (handOver < 0) closed else closed.take(handOver), "")
        val started = TAGS.maxOf { tag -> begun(open, tag) }
        return open.dropLast(started).trimEnd()
    }

    /** How many of [tag]'s first letters [text] ends with: a tag that has only begun to arrive. */
    private fun begun(
        text: String,
        tag: String,
    ): Int = (tag.length - 1 downTo 1).firstOrNull { text.endsWith(tag.take(it), ignoreCase = true) } ?: 0
}
