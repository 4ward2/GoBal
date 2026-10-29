package com.danielealbano.androidremotecontrolmcp.ui.chat

import com.danielealbano.androidremotecontrolmcp.services.chat.Attachment

/** The two buttons on a running phone task's card. */
class TaskActions(
    val onStop: () -> Unit = {},
    val onTakeOver: () -> Unit = {},
)

/** What a message row can ask for. */
class BubbleActions(
    val onLongPress: (ChatItem) -> Unit,
    val onQuoteTap: (String) -> Unit,
    val onImageTap: (Attachment) -> Unit,
    val onFileTap: (Attachment) -> Unit,
    /** A plain tap on the bubble: picks it while several messages are being selected. */
    val onTap: (ChatItem) -> Unit = {},
    val task: TaskActions = TaskActions(),
) {
    companion object {
        /** For a row that is not a stored message yet (the reply still being written). */
        val None = BubbleActions(onLongPress = {}, onQuoteTap = {}, onImageTap = {}, onFileTap = {})
    }
}
