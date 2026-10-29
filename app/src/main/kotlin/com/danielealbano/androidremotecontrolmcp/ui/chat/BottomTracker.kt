package com.danielealbano.androidremotecontrolmcp.ui.chat

/** Whether the reader has wandered far enough from the newest message to want a way back. Pure. */
object BottomTracker {
    /** Rows that may sit below the last visible one before the "back to bottom" button appears. */
    const val FAR_ROWS = 3

    /**
     * True when at least [FAR_ROWS] rows lie below [lastVisible] (the index of the last row on
     * screen, null before the first layout) in a list whose last row is [lastIndex].
     */
    fun isFar(
        lastVisible: Int?,
        lastIndex: Int,
    ): Boolean = lastVisible != null && lastIndex - lastVisible >= FAR_ROWS
}
