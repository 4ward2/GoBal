package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Writes the composer's text to disk a moment after the last keystroke, so leaving the chat (or
 * the app being killed) does not lose a half-written message.
 */
internal class DraftSaver(
    private val store: ConversationStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private var pending: Job? = null

    /** The draft is now [text]; saved for the conversation that is active right now. */
    fun changed(text: String) {
        pending?.cancel()
        pending =
            scope.launch(ioDispatcher) {
                // Resolved before waiting: switching conversation meanwhile must not move the draft.
                val key = store.draftKey()
                delay(QUIET_MS)
                store.drafts.save(key, text)
            }
    }

    /** The draft was sent: forget it at once. */
    fun sent() {
        pending?.cancel()
        pending = scope.launch(ioDispatcher) { store.drafts.save(store.draftKey(), "") }
    }

    private companion object {
        const val QUIET_MS = 400L
    }
}
