package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The text a field shows, kept in the composition while its value also lives in a view model.
 *
 * Reading the field's value back from the view model's flow lags behind the keyboard: fast typing,
 * or an input method committing several characters at once, then loses characters. So the field
 * owns its text, and the view model's copy is only taken over when it is a change the field did
 * not make itself (the paste button, picking another service). Echoes of the field's own edits
 * arrive late and possibly out of date; they are recognised and ignored.
 */
@Stable
class LocalText(
    initial: String,
) {
    var text by mutableStateOf(initial)
        private set

    /** Values this field sent on that have not come back yet, oldest first. */
    private val pending = ArrayDeque<String>()

    /** The user typed [value]; [outgoing] is what was sent to the view model (e.g. trimmed). */
    fun typed(
        value: String,
        outgoing: String,
    ) {
        text = value
        pending.addLast(outgoing)
    }

    /** The view model's value changed to [external]. */
    fun sync(external: String) {
        val echo = pending.indexOf(external)
        if (echo >= 0) {
            repeat(echo + 1) { pending.removeFirst() }
        } else if (external != text) {
            text = external
            pending.clear()
        }
    }
}

@Composable
fun rememberLocalText(external: String): LocalText {
    val local = remember { LocalText(external) }
    LaunchedEffect(external) { local.sync(external) }
    return local
}
