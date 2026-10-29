package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.ui.graphics.ImageBitmap

/** Who the assistant side of the chat is right now: the assistant, or the active character with its portrait. */
data class Speaker(
    val name: String,
    val avatar: ImageBitmap? = null,
)
