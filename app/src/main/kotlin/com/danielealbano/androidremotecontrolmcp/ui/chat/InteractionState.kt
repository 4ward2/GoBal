package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.danielealbano.androidremotecontrolmcp.services.chat.Attachment

/** Which sheet or dialog is open over the conversation, if any. */
@Stable
class InteractionState {
    var menuFor by mutableStateOf<ChatItem?>(null)
    var forwarding by mutableStateOf<ChatItem?>(null)
    var selecting by mutableStateOf<ChatItem?>(null)
    var viewing by mutableStateOf<Attachment?>(null)

    /** The user's message being edited before it is sent again. */
    var editing by mutableStateOf<ChatItem?>(null)

    /** The reply whose regeneration is waiting for a "this drops later messages" confirmation. */
    var regenerating by mutableStateOf<ChatItem?>(null)

    /** The ids picked while selecting several messages; null when not in that mode. */
    var selected by mutableStateOf<Set<String>?>(null)

    /** Picks or un-picks [id]; the mode stays on even with nothing picked. */
    fun toggle(id: String) {
        val now = selected ?: return
        selected = if (id in now) now - id else now + id
    }
}

@Composable
fun rememberInteractionState(): InteractionState = remember { InteractionState() }
