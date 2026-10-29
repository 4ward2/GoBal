package com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteMode
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteModel
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.AgentDetails
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.PaseoAgentAdmin
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AgentAdminUiState(
    val details: AgentDetails? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val modes: List<RemoteMode> = emptyList(),
    val models: List<RemoteModel> = emptyList(),
    val archived: Boolean = false,
    val computerId: String = "",
    val agentId: String = "",
)

@HiltViewModel
class AgentAdminViewModel
    @Inject
    constructor(
        private val computers: RemoteComputers,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _state = MutableStateFlow(AgentAdminUiState())
        val state: StateFlow<AgentAdminUiState> = _state.asStateFlow()

        fun open(
            computerId: String,
            agentId: String,
        ) {
            viewModelScope.launch(ioDispatcher) {
                _state.value =
                    AgentAdminUiState(
                        loading = true,
                        computerId = computerId,
                        agentId = agentId,
                    )

                val rpc = computers.rpc(computerId)
                if (rpc == null) {
                    _state.value =
                        AgentAdminUiState(
                            error = "Computer not found",
                            computerId = computerId,
                            agentId = agentId,
                        )
                    return@launch
                }

                val admin = PaseoAgentAdmin(rpc)
                val detailsResult = admin.details(agentId)

                detailsResult.fold(
                    onSuccess = { details ->
                        _state.value =
                            AgentAdminUiState(
                                details = details,
                                archived = details.archived,
                                loading = false,
                                computerId = computerId,
                                agentId = agentId,
                            )
                        loadModeAndModels(computerId, details.provider)
                    },
                    onFailure = { error ->
                        _state.value =
                            AgentAdminUiState(
                                error = error.message ?: "Failed to load agent details",
                                computerId = computerId,
                                agentId = agentId,
                            )
                    },
                )
            }
        }

        fun rename(newTitle: String) {
            val details = _state.value.details ?: return
            val computerId = _state.value.computerId.ifBlank { return }

            viewModelScope.launch(ioDispatcher) {
                val rpc = computers.rpc(computerId) ?: return@launch
                val admin = PaseoAgentAdmin(rpc)

                val result = admin.rename(details.id, newTitle)
                result.fold(
                    onSuccess = {
                        _state.value =
                            _state.value.copy(details = details.copy(title = newTitle), error = null)
                    },
                    onFailure = { error ->
                        _state.value =
                            _state.value.copy(error = error.message ?: "Failed to rename agent")
                    },
                )
            }
        }

        fun archive() {
            val details = _state.value.details ?: return
            val computerId = _state.value.computerId.ifBlank { return }

            viewModelScope.launch(ioDispatcher) {
                val rpc = computers.rpc(computerId) ?: return@launch
                val admin = PaseoAgentAdmin(rpc)

                val result = admin.archive(details.id)
                result.fold(
                    onSuccess = {
                        _state.value =
                            _state.value.copy(
                                archived = true,
                                details = details.copy(archived = true),
                                error = null,
                            )
                    },
                    onFailure = { error ->
                        _state.value =
                            _state.value.copy(error = error.message ?: "Failed to archive agent")
                    },
                )
            }
        }

        fun resume() {
            val details = _state.value.details ?: return
            val computerId = _state.value.computerId.ifBlank { return }

            viewModelScope.launch(ioDispatcher) {
                val rpc = computers.rpc(computerId) ?: return@launch
                val admin = PaseoAgentAdmin(rpc)

                val detailsResult = admin.details(details.id)
                detailsResult.fold(
                    onSuccess = { newDetails ->
                        admin.resume(newDetails.persistence).fold(
                            onSuccess = {
                                _state.value =
                                    _state.value.copy(
                                        archived = false,
                                        details = newDetails.copy(archived = false),
                                        error = null,
                                    )
                            },
                            onFailure = { error ->
                                _state.value =
                                    _state.value.copy(
                                        error = error.message ?: "Failed to resume agent",
                                    )
                            },
                        )
                    },
                    onFailure = { error ->
                        _state.value =
                            _state.value.copy(
                                error = error.message ?: "Failed to load agent for resuming",
                            )
                    },
                )
            }
        }

        fun delete() {
            val details = _state.value.details ?: return
            val computerId = _state.value.computerId.ifBlank { return }

            viewModelScope.launch(ioDispatcher) {
                val rpc = computers.rpc(computerId) ?: return@launch
                val admin = PaseoAgentAdmin(rpc)

                val result = admin.delete(details.id)
                result.fold(
                    onSuccess = {
                        _state.value = _state.value.copy(error = "Agent deleted")
                    },
                    onFailure = { error ->
                        _state.value =
                            _state.value.copy(error = error.message ?: "Failed to delete agent")
                    },
                )
            }
        }

        fun setMode(modeId: String) {
            val details = _state.value.details ?: return
            val computerId = _state.value.computerId.ifBlank { return }

            viewModelScope.launch(ioDispatcher) {
                val rpc = computers.rpc(computerId) ?: return@launch
                val admin = PaseoAgentAdmin(rpc)

                val result = admin.setMode(details.id, modeId)
                result.fold(
                    onSuccess = {
                        _state.value =
                            _state.value.copy(
                                details = details.copy(modeId = modeId),
                                error = null,
                            )
                    },
                    onFailure = { error ->
                        _state.value =
                            _state.value.copy(error = error.message ?: "Failed to set mode")
                    },
                )
            }
        }

        fun setModel(modelId: String?) {
            val details = _state.value.details ?: return
            val computerId = _state.value.computerId.ifBlank { return }

            viewModelScope.launch(ioDispatcher) {
                val rpc = computers.rpc(computerId) ?: return@launch
                val admin = PaseoAgentAdmin(rpc)

                val result = admin.setModel(details.id, modelId)
                result.fold(
                    onSuccess = {
                        _state.value =
                            _state.value.copy(
                                details = details.copy(model = modelId),
                                error = null,
                            )
                    },
                    onFailure = { error ->
                        _state.value =
                            _state.value.copy(error = error.message ?: "Failed to set model")
                    },
                )
            }
        }

        fun setThinking(thinkingOptionId: String?) {
            val details = _state.value.details ?: return
            val computerId = _state.value.computerId.ifBlank { return }

            viewModelScope.launch(ioDispatcher) {
                val rpc = computers.rpc(computerId) ?: return@launch
                val admin = PaseoAgentAdmin(rpc)

                val result = admin.setThinking(details.id, thinkingOptionId)
                result.fold(
                    onSuccess = {
                        _state.value =
                            _state.value.copy(
                                details = details.copy(thinkingOptionId = thinkingOptionId),
                                error = null,
                            )
                    },
                    onFailure = { error ->
                        _state.value =
                            _state.value.copy(error = error.message ?: "Failed to set thinking")
                    },
                )
            }
        }

        fun refresh() {
            val details = _state.value.details ?: return
            val computerId = _state.value.computerId.ifBlank { return }

            viewModelScope.launch(ioDispatcher) {
                val rpc = computers.rpc(computerId) ?: return@launch
                val admin = PaseoAgentAdmin(rpc)

                val result = admin.refresh(details.id)
                result.fold(
                    onSuccess = {
                        open(computerId, details.id)
                    },
                    onFailure = { error ->
                        _state.value =
                            _state.value.copy(error = error.message ?: "Failed to refresh agent")
                    },
                )
            }
        }

        private fun loadModeAndModels(
            computerId: String,
            provider: String,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val session = computers.session(computerId) ?: return@launch
                val choicesResult = session.catalog.choices(provider)

                choicesResult.fold(
                    onSuccess = { choices ->
                        _state.value =
                            _state.value.copy(
                                modes = choices.modes,
                                models = choices.models,
                            )
                    },
                    onFailure = { /* Ignore errors, choices are optional */ },
                )
            }
        }
    }
