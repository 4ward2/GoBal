package com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.hooks.GoalRequest
import com.danielealbano.androidremotecontrolmcp.services.remote.hooks.GoalRun
import com.danielealbano.androidremotecontrolmcp.services.remote.hooks.GoalRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The goal of one agent on one computer: the latest one, active or finished. */
@HiltViewModel
class GoalViewModel
    @Inject
    constructor(
        private val computers: RemoteComputers,
        private val runner: GoalRunner,
    ) : ViewModel() {
        private val ids = MutableStateFlow<Pair<String, String>?>(null)
        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        val goal: StateFlow<GoalRun?> =
            combine(ids, runner.goals) { key, goals ->
                key ?: return@combine null
                goals
                    .filter { it.computerId == key.first && it.agentId == key.second }
                    .maxWithOrNull(compareBy<GoalRun>({ it.state.active }, { it.updatedAt }))
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        fun open(
            computerId: String,
            agentId: String,
        ) {
            ids.value = computerId to agentId
        }

        fun start(request: GoalRequest) {
            val (computerId, agentId) = ids.value ?: return
            viewModelScope.launch {
                val computer = computers.computers.value.firstOrNull { it.id == computerId }
                val session = computers.session(computerId)
                val agent =
                    session?.agents?.value?.firstOrNull { it.id == agentId }
                        ?: session?.refresh()?.getOrNull()?.firstOrNull { it.id == agentId }
                val result =
                    if (computer == null || agent == null) {
                        Result.failure(IllegalStateException(NOT_FOUND))
                    } else {
                        runner.start(computer, agent, request)
                    }
                _error.value = result.exceptionOrNull()?.message
            }
        }

        fun stop() {
            goal.value?.let { runner.stop(it.id) }
        }

        fun nudge() {
            val id = goal.value?.id ?: return
            viewModelScope.launch { _error.value = runner.nudge(id).exceptionOrNull()?.message }
        }

        fun dismissError() {
            _error.value = null
        }

        private companion object {
            const val NOT_FOUND = "找不到这台电脑或这个代理，刷新后再试"
        }
    }
