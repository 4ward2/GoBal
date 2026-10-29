package com.danielealbano.androidremotecontrolmcp.services.chat

/**
 * A request for the phone that the local rules did not recognise reaches the chat model, which
 * cannot act; the prompt has it answer with `<phone_task>…</phone_task>` instead, and the app
 * then hands the user's own words to the phone agent. Pure.
 */
object PhoneHandoff {
    const val OPEN = "<phone_task>"
    private const val CLOSE = "</phone_task>"

    /**
     * The task the model handed over instead of answering, as it restated it (what follows the
     * tag when it was left unclosed); null when the reply is an answer.
     */
    fun taskIn(reply: String): String? {
        val start = reply.indexOf(OPEN, ignoreCase = true)
        if (start < 0) return null
        val rest = reply.substring(start + OPEN.length)
        val end = rest.indexOf(CLOSE, ignoreCase = true)
        return (if (end < 0) rest else rest.take(end)).trim()
    }
}
