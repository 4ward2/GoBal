package com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.DaemonConfigView
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.PaseoDaemonAdmin
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.ProviderHealth
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.ProviderStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DaemonAdminUiState(
    val config: DaemonConfigView? = null,
    val providers: List<ProviderStatus> = emptyList(),
    val diagnostics: Map<String, ProviderHealth> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String = "",
    val isRestarting: Boolean = false,
)

/**
 * Manages the daemon settings view: config, provider health, and restart operations.
 * Opens with [open] and loads the initial config and provider list.
 */
@HiltViewModel
class DaemonAdminViewModel
    @Inject
    constructor(
        private val remoteComputers: RemoteComputers,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        companion object {
            private const val MAX_DIAGNOSTIC_MESSAGE_LENGTH = 120
        }

        private val _uiState = MutableStateFlow(DaemonAdminUiState())
        val uiState: StateFlow<DaemonAdminUiState> = _uiState.asStateFlow()

        private var currentComputerId: String = ""
        private var admin: PaseoDaemonAdmin? = null

        fun open(computerId: String) {
            if (currentComputerId == computerId && admin != null) return
            currentComputerId = computerId
            loadConfig()
            loadProviders()
        }

        private fun loadConfig() {
            viewModelScope.launch(ioDispatcher) {
                _uiState.value = _uiState.value.copy(isLoading = true, error = "")
                val admin =
                    admin ?: buildAdmin() ?: run {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = "Computer not connected")
                        return@launch
                    }
                this@DaemonAdminViewModel.admin = admin

                val result = admin.config()
                result
                    .onSuccess { config ->
                        _uiState.value = _uiState.value.copy(config = config, isLoading = false)
                    }.onFailure { error ->
                        val errorMsg = error.message ?: "Failed to load config"
                        _uiState.value = _uiState.value.copy(isLoading = false, error = errorMsg)
                    }
            }
        }

        private fun loadProviders() {
            viewModelScope.launch(ioDispatcher) {
                val admin = admin ?: buildAdmin() ?: return@launch
                this@DaemonAdminViewModel.admin = admin

                val result = admin.providers()
                result
                    .onSuccess { providers ->
                        _uiState.value = _uiState.value.copy(providers = providers)
                    }.onFailure { error ->
                        val msg = error.message ?: "Failed to load providers"
                        _uiState.value = _uiState.value.copy(error = msg)
                    }
            }
        }

        fun diagnoseProvider(provider: String) {
            viewModelScope.launch(ioDispatcher) {
                val admin = admin ?: return@launch
                val result = admin.diagnose(provider)
                result
                    .onSuccess { health ->
                        val diagnostics = _uiState.value.diagnostics.toMutableMap()
                        diagnostics[provider] = health
                        _uiState.value = _uiState.value.copy(diagnostics = diagnostics)
                    }.onFailure { error ->
                        val msg = error.message ?: "Diagnostic failed"
                        val diagnostics = _uiState.value.diagnostics.toMutableMap()
                        diagnostics[provider] =
                            ProviderHealth(
                                provider = provider,
                                ok = false,
                                message = msg.take(MAX_DIAGNOSTIC_MESSAGE_LENGTH),
                            )
                        _uiState.value = _uiState.value.copy(diagnostics = diagnostics)
                    }
            }
        }

        fun refreshProviders() {
            viewModelScope.launch(ioDispatcher) {
                val admin = admin ?: return@launch
                _uiState.value = _uiState.value.copy(isLoading = true, error = "")
                val result = admin.refreshProviders()
                result
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                        loadProviders()
                    }.onFailure { error ->
                        val msg = error.message ?: "Refresh failed"
                        _uiState.value = _uiState.value.copy(isLoading = false, error = msg)
                    }
            }
        }

        fun restartServer() {
            viewModelScope.launch(ioDispatcher) {
                val admin = admin ?: return@launch
                _uiState.value = _uiState.value.copy(isRestarting = true, error = "")
                val result = admin.restart()
                result
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(isRestarting = false)
                    }.onFailure { error ->
                        val msg = error.message ?: "Restart failed"
                        _uiState.value = _uiState.value.copy(isRestarting = false, error = msg)
                    }
            }
        }

        private suspend fun buildAdmin(): PaseoDaemonAdmin? {
            return withContext(ioDispatcher) {
                val rpc = remoteComputers.rpc(currentComputerId) ?: return@withContext null
                PaseoDaemonAdmin(rpc)
            }
        }
    }
