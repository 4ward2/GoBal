package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBackend
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBubble
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Saves [messages] as the conversation [at]; before any conversation was shown, as the open one. */
internal suspend fun ChatBackend.keep(
    at: ConversationRef?,
    messages: List<ChatBubble>,
) {
    store.saveAt(at ?: store.here(), messages)
}

/** Changes the chat state only while [at] is still the conversation on screen. */
internal fun MutableStateFlow<NativeChatUiState>.updateAt(
    at: ConversationRef?,
    change: (NativeChatUiState) -> NativeChatUiState,
) = update { if (it.conversation == at) change(it) else it }
