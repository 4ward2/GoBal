package com.danielealbano.androidremotecontrolmcp.services.im.render

/** One piece of a rendered reply, in the order it should be sent to the channel. */
sealed interface OutboundPart {
    data class Text(
        val text: String,
    ) : OutboundPart

    /**
     * A block better shown as a picture. [source] is the block's original Markdown, for drawing
     * it; [fallback] is what to send instead when the picture cannot be made or delivered.
     */
    data class Picture(
        val kind: PictureKind,
        val source: String,
        val fallback: String,
    ) : OutboundPart
}

enum class PictureKind { TABLE, CODE, FORMULA }
