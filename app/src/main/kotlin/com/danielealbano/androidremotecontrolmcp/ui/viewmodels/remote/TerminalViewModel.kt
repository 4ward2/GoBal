package com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.PaseoTerminals
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.TerminalInfo
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.TerminalScreenState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TerminalUiState(
    val terminals: List<TerminalInfo> = emptyList(),
    val selectedTerminalId: String? = null,
    val screenState: TerminalScreenState? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * The terminals of one workspace on a computer. The open one is captured once a second
 * (its live output only travels as binary frames, which the phone's channel does not carry).
 */
@HiltViewModel
class TerminalViewModel
    @Inject
    constructor(
        private val remoteComputers: RemoteComputers,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(TerminalUiState())
        val state: StateFlow<TerminalUiState> = mutableState.asStateFlow()

        private var computerId: String? = null
        private var watching: Job? = null

        val input = TerminalInput(::client, viewModelScope, ioDispatcher, mutableState)

        fun open(
            computerId: String,
            cwd: String,
        ) {
            this.computerId = computerId
            viewModelScope.launch(ioDispatcher) {
                val client = client() ?: return@launch failed(NOT_CONNECTED)
                mutableState.update { it.copy(isLoading = true) }
                client
                    .list(cwd)
                    .onSuccess { found ->
                        mutableState.update { it.copy(terminals = found, isLoading = false, error = null) }
                        found.firstOrNull()?.let { selectTerminal(it.id) }
                    }.onFailure { failed(UNREADABLE) }
            }
        }

        fun createTerminal(
            cwd: String,
            name: String? = null,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val client = client() ?: return@launch failed(NOT_CONNECTED)
                mutableState.update { it.copy(isLoading = true) }
                client
                    .create(cwd, name)
                    .onSuccess { made ->
                        mutableState.update { now ->
                            now.copy(terminals = now.terminals + made, isLoading = false, error = null)
                        }
                        selectTerminal(made.id)
                    }.onFailure { failed(NOT_CREATED) }
            }
        }

        fun selectTerminal(terminalId: String) {
            watching?.cancel()
            mutableState.update { it.copy(selectedTerminalId = terminalId, screenState = null) }
            watching =
                viewModelScope.launch(ioDispatcher) {
                    val client = client() ?: return@launch failed(NOT_CONNECTED)
                    client.startPolling(terminalId).collect { screen ->
                        mutableState.update { it.copy(screenState = screen) }
                    }
                }
        }

        fun deselectTerminal() {
            watching?.cancel()
            mutableState.update { it.copy(selectedTerminalId = null, screenState = null) }
        }

        fun killTerminal(terminalId: String) {
            viewModelScope.launch(ioDispatcher) {
                val client = client() ?: return@launch failed(NOT_CONNECTED)
                client
                    .kill(terminalId)
                    .onSuccess {
                        if (state.value.selectedTerminalId == terminalId) deselectTerminal()
                        mutableState.update { it.copy(terminals = it.terminals.filterNot { t -> t.id == terminalId }) }
                    }.onFailure { failed(NOT_KILLED) }
            }
        }

        fun clearError() {
            mutableState.update { it.copy(error = null) }
        }

        private fun failed(message: String) {
            mutableState.update { it.copy(isLoading = false, error = message) }
        }

        private fun client(): PaseoTerminals? = computerId?.let(remoteComputers::rpc)?.let(::PaseoTerminals)

        private companion object {
            const val NOT_CONNECTED = "电脑没有连接"
            const val UNREADABLE = "读不到这台电脑上的终端"
            const val NOT_CREATED = "没能在电脑上新建终端"
            const val NOT_KILLED = "没能关掉这个终端"
        }
    }
