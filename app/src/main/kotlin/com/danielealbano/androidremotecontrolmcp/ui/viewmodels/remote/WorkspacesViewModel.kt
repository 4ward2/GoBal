package com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.PaseoWorkspaceNodes
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.PaseoWorkspaces
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.WorkspaceChange
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.WorkspaceChange.Removed
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.WorkspaceChange.Upserted
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.WorkspaceInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State for the workspaces list. */
data class WorkspacesUiState(
    val workspaces: List<WorkspaceInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/** ViewModel for workspaces list and operations. */
@HiltViewModel
class WorkspacesViewModel
    @Inject
    constructor(
        private val computers: RemoteComputers,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _state = MutableStateFlow(WorkspacesUiState())
        val state: StateFlow<WorkspacesUiState> = _state.asStateFlow()

        fun open(computerId: String) {
            viewModelScope.launch(ioDispatcher) {
                _state.value = WorkspacesUiState(isLoading = true)
                val workspaces =
                    computers.rpc(computerId)?.let { rpc ->
                        PaseoWorkspaces(rpc).list()
                    } ?: Result.failure(Exception("Computer not found"))

                workspaces.fold(
                    onSuccess = { response ->
                        _state.value = WorkspacesUiState(workspaces = response.entries)
                        listenForUpdates(computerId)
                    },
                    onFailure = { error ->
                        _state.value = WorkspacesUiState(error = error.message)
                    },
                )
            }
        }

        fun startScript(
            computerId: String,
            workspaceId: String,
            scriptName: String,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val rpc = computers.rpc(computerId) ?: return@launch
                PaseoWorkspaces(rpc)
                    .startScript(workspaceId, scriptName)
                    .onFailure { error ->
                        _state.value = _state.value.copy(error = error.message)
                    }
            }
        }

        fun archive(
            computerId: String,
            workspaceId: String,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val rpc = computers.rpc(computerId) ?: return@launch
                PaseoWorkspaces(rpc)
                    .archive(workspaceId)
                    .onFailure { error ->
                        _state.value = _state.value.copy(error = error.message)
                    }
            }
        }

        private fun listenForUpdates(computerId: String) {
            viewModelScope.launch(ioDispatcher) {
                val rpc = computers.rpc(computerId) ?: return@launch
                PaseoWorkspaces(rpc)
                    .updates()
                    .collect { frame ->
                        if (frame.type != "workspace_update") return@collect
                        val change = PaseoWorkspaceNodes.workspaceChange(frame.payload)
                        when (change) {
                            is Upserted -> {
                                updateWorkspace(change.workspace)
                            }

                            is Removed -> {
                                removeWorkspace(change.workspaceId)
                            }

                            null -> {}
                        }
                    }
            }
        }

        private fun updateWorkspace(workspace: WorkspaceInfo) {
            val current = _state.value.workspaces
            val updated =
                current
                    .map { if (it.id == workspace.id) workspace else it }
                    .ifEmpty { current + workspace }
            _state.value = _state.value.copy(workspaces = updated)
        }

        private fun removeWorkspace(workspaceId: String) {
            val updated = _state.value.workspaces.filter { it.id != workspaceId }
            _state.value = _state.value.copy(workspaces = updated)
        }
    }
