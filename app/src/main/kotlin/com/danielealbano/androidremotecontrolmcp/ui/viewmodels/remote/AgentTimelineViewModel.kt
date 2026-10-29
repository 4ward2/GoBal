package com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.PaseoTimeline
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.TimelineEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

data class AgentTimelineUiState(
    val loading: Boolean = true,
    val entries: List<TimelineEntry> = emptyList(),
    val error: String? = null,
)

/**
 * Everything an agent on a computer did — its words, tool calls, edits, to-dos — read from the
 * daemon's timeline. The chat shows only the conversation; this is the full record.
 */
@HiltViewModel
class AgentTimelineViewModel
    @Inject
    constructor(
        private val computers: RemoteComputers,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val state = MutableStateFlow(AgentTimelineUiState())
        val ui: StateFlow<AgentTimelineUiState> = state.asStateFlow()

        fun load(
            computerId: String,
            agentId: String,
        ) {
            state.update { it.copy(loading = true, error = null) }
            viewModelScope.launch(ioDispatcher) {
                val rpc = computers.rpc(computerId)
                if (rpc == null) {
                    state.update { it.copy(loading = false, error = NOT_CONNECTED) }
                    return@launch
                }
                // fetch_agent_timeline_request (messages.ts): the newest page, chunks merged by the daemon.
                val fields =
                    buildJsonObject {
                        put("agentId", agentId)
                        put("direction", "tail")
                        put("limit", PAGE)
                        put("projection", "projected")
                    }
                rpc
                    .call("fetch_agent_timeline_request", fields)
                    .onSuccess { payload -> state.update { AgentTimelineUiState(false, PaseoTimeline.page(payload)) } }
                    .onFailure { state.update { it.copy(loading = false, error = UNREADABLE) } }
            }
        }

        private companion object {
            const val PAGE = 200
            const val NOT_CONNECTED = "电脑没有连接"
            const val UNREADABLE = "读不到这个代理的记录"
        }
    }
