package com.danielealbano.androidremotecontrolmcp.services.im.render

/**
 * What a chat channel can carry. [ChannelRenderer] only ever looks at these four facts; it never
 * special-cases a particular channel by name.
 */
data class ChannelTraits(
    /** The channel shows Markdown itself, so a reply can be sent unchanged. */
    val markdown: Boolean,
    /** The channel can carry a picture message. */
    val images: Boolean,
    /** The channel can carry a file message. */
    val files: Boolean,
    /** The longest a single text message can be. */
    val maxChars: Int,
)
