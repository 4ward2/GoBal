package com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote

import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.PaseoTerminals
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.TerminalClientMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the user types into the open terminal, and the keys a phone keyboard lacks. After each
 * input the screen is captured once at once, so the answer shows without waiting for the next poll.
 */
class TerminalInput internal constructor(
    private val terminals: () -> PaseoTerminals?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val state: MutableStateFlow<TerminalUiState>,
) {
    fun sendText(text: String) {
        val terminalId = state.value.selectedTerminalId ?: return
        scope.launch(io) {
            val client = terminals() ?: return@launch
            client
                .send(terminalId, TerminalClientMessage.Input(text))
                .onSuccess {
                    client.refreshScreen(terminalId).onSuccess { screen ->
                        state.update { it.copy(screenState = screen, error = null) }
                    }
                }.onFailure { state.update { it.copy(error = NOT_SENT) } }
        }
    }

    /** [keyName] is one of the key chips: Enter, Tab, Escape, Ctrl-C and the four arrows. */
    fun sendKey(keyName: String) {
        KEYS[keyName]?.let(::sendText)
    }

    private companion object {
        const val NOT_SENT = "没能发到电脑上的终端"
        val KEYS =
            mapOf(
                "Enter" to "\r",
                "Tab" to "\t",
                "Escape" to "\u001b",
                "Ctrl-C" to "\u0003",
                "Up" to "\u001b[A",
                "Down" to "\u001b[B",
                "Right" to "\u001b[C",
                "Left" to "\u001b[D",
            )
    }
}
