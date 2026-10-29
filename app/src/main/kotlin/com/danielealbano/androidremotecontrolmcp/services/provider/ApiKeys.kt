package com.danielealbano.androidremotecontrolmcp.services.provider

/** An API key found in a sentence, and the service its shape points to (null when it could be many). */
data class FoundKey(
    val key: String,
    val presetId: String?,
)

/**
 * Recognises API keys by their shape. Nothing here talks to a network: a key is never tried
 * against several services to guess whose it is, since that would hand it to all of them.
 */
object ApiKeys {
    private const val EDGE_BEFORE = "(?<![A-Za-z0-9_.-])"
    private const val EDGE_AFTER = "(?![A-Za-z0-9_-])"

    private fun shaped(body: String) = Regex(EDGE_BEFORE + body + EDGE_AFTER)

    /** Most specific first: "sk-ant-…" must not be taken for a generic "sk-…". */
    private val SHAPES: List<Pair<Regex, String?>> =
        listOf(
            shaped("sk-ant-[A-Za-z0-9_-]{20,}") to "anthropic",
            shaped("sk-or-v1-[A-Za-z0-9]{20,}") to "openrouter",
            shaped("sk-proj-[A-Za-z0-9_-]{20,}") to "openai",
            shaped("AIza[0-9A-Za-z_-]{30,}") to "gemini",
            shaped("gsk_[A-Za-z0-9]{20,}") to "groq",
            shaped("xai-[A-Za-z0-9]{20,}") to "xai",
            shaped("[0-9a-f]{32}\\.[A-Za-z0-9]{16}") to "zhipu",
            shaped("user_[A-Za-z0-9]{40,}") to "commandcode",
            shaped("sk-[A-Za-z0-9_-]{20,}") to null,
        )

    /**
     * Any long token of letters and digits (UUID-style keys, bare hex): only taken as a key when
     * the sentence talks about keys or services, since hashes and IDs look the same.
     */
    private val GENERIC = shaped("[A-Za-z0-9][A-Za-z0-9_.-]{31,}")

    /** The first key in [text]; generic-looking tokens count only when [aboutKeys]. */
    fun find(
        text: String,
        aboutKeys: Boolean,
    ): FoundKey? {
        val specific =
            SHAPES.firstNotNullOfOrNull { (shape, preset) -> shape.find(text)?.let { FoundKey(it.value, preset) } }
        val generic =
            GENERIC
                .findAll(text)
                .map { it.value }
                .firstOrNull { token -> aboutKeys && looksRandom(token) }
                ?.let { FoundKey(it, null) }
        return specific ?: generic
    }

    /** Keys mix letters and digits; words and plain numbers do not. */
    private fun looksRandom(token: String): Boolean = token.any { it.isDigit() } && token.any { it.isLetter() }
}
