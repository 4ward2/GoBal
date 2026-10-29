package com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRunner
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.hooks.GoalRun
import com.danielealbano.androidremotecontrolmcp.services.remote.hooks.GoalRunner
import com.danielealbano.androidremotecontrolmcp.ui.screens.remote.DashboardRow
import com.danielealbano.androidremotecontrolmcp.ui.screens.remote.agentRunToDashboardRow
import com.danielealbano.androidremotecontrolmcp.ui.screens.remote.remoteAgentToDashboardRow
import com.danielealbano.androidremotecontrolmcp.ui.screens.remote.sortDashboardRows
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Aggregates all running tasks: phone's own task + all agents on all bound computers.
 * Sorted by state (waiting for you first) then by recency.
 */
@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        private val computers: RemoteComputers,
        private val agentRunner: AgentRunner,
        private val goals: GoalRunner,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val rows: StateFlow<List<DashboardRow>> =
            combine(
                computers.computers,
                agentRunner.runs.current,
                goals.goals,
                ::buildRows,
            ).flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

        private fun buildRows(
            computers: List<com.danielealbano.androidremotecontrolmcp.services.remote.PairedComputer>,
            currentRun: com.danielealbano.androidremotecontrolmcp.services.agent.AgentRun?,
            goalRuns: List<GoalRun>,
        ): List<DashboardRow> {
            val active = goalRuns.filter { it.state.active }
            val rows = mutableListOf<DashboardRow>()

            if (currentRun != null) {
                rows.add(agentRunToDashboardRow(currentRun))
            }

            for (computer in computers) {
                val session = this.computers.session(computer.id) ?: continue
                val agents = session.agents.value
                val status = session.status.value

                for (agent in agents) {
                    rows.add(
                        remoteAgentToDashboardRow(
                            computerName = computer.name,
                            agent = agent,
                            linkState = status.state,
                            computerId = computer.id,
                        ).copy(goal = active.firstOrNull { it.computerId == computer.id && it.agentId == agent.id }),
                    )
                }
            }

            return sortDashboardRows(rows)
        }
    }
