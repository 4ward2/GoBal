@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRun
import com.danielealbano.androidremotecontrolmcp.services.agent.RunStatus
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkState
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteAgent
import com.danielealbano.androidremotecontrolmcp.services.remote.hooks.GoalRun

private const val MAX_TITLE_LENGTH = 60

enum class DashboardRowState {
    WORKING,
    WAITING_FOR_YOU,
    IDLE,
    FAILED,
    OFFLINE,
}

/**
 * A single row in the dashboard: one phone task or one computer agent.
 * Sorted by state (waiting first), then by recency.
 */
data class DashboardRow(
    val source: String,
    val title: String,
    val status: String,
    val state: DashboardRowState,
    val updatedAt: Long,
    val computerId: String? = null,
    val agentId: String? = null,
    /** The agent's goal in 「目标模式」, while one is active. */
    val goal: GoalRun? = null,
)

fun agentRunToDashboardRow(run: AgentRun): DashboardRow {
    val state =
        when {
            run.status == RunStatus.WAITING_APPROVAL -> DashboardRowState.WAITING_FOR_YOU
            run.status == RunStatus.RUNNING -> DashboardRowState.WORKING
            run.status == RunStatus.DONE -> DashboardRowState.IDLE
            run.status == RunStatus.FAILED -> DashboardRowState.FAILED
            run.status == RunStatus.STOPPED -> DashboardRowState.IDLE
            run.status == RunStatus.HANDED_OVER -> DashboardRowState.WAITING_FOR_YOU
            else -> DashboardRowState.IDLE
        }
    return DashboardRow(
        source = "手机",
        title = run.goal.take(MAX_TITLE_LENGTH),
        status = agentRunStatus(run),
        state = state,
        updatedAt = maxOf(run.startedAt, run.endedAt),
    )
}

private fun agentRunStatus(run: AgentRun): String =
    when {
        run.status == RunStatus.WAITING_APPROVAL -> "等待确认 · ${run.steps.count { it.ok }} 步"
        run.status == RunStatus.RUNNING -> "第 ${run.steps.size + 1} 步"
        run.status == RunStatus.DONE -> "${run.steps.size} 步 · 完成"
        run.status == RunStatus.FAILED -> "${run.steps.size} 步 · 失败"
        run.status == RunStatus.STOPPED -> "${run.steps.size} 步 · 停止"
        run.status == RunStatus.HANDED_OVER -> "需要人工操作"
        else -> ""
    }

fun remoteAgentToDashboardRow(
    computerName: String,
    agent: RemoteAgent,
    linkState: LinkState,
    computerId: String,
): DashboardRow {
    val state =
        when {
            linkState != LinkState.ONLINE -> DashboardRowState.OFFLINE
            agent.pending.isNotEmpty() -> DashboardRowState.WAITING_FOR_YOU
            agent.status == "running" -> DashboardRowState.WORKING
            agent.status == "error" -> DashboardRowState.FAILED
            else -> DashboardRowState.IDLE
        }
    return DashboardRow(
        source = computerName,
        title = agent.title.take(MAX_TITLE_LENGTH),
        status = remoteAgentStatus(agent, linkState),
        state = state,
        updatedAt = agent.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
        computerId = computerId,
        agentId = agent.id,
    )
}

private fun remoteAgentStatus(
    agent: RemoteAgent,
    linkState: LinkState,
): String =
    when {
        linkState != LinkState.ONLINE -> "离线"
        agent.pending.isNotEmpty() -> "等待批准"
        agent.status == "running" -> agent.model
        agent.status == "error" -> "出错"
        else -> "闲置 · ${agent.model}"
    }

fun sortDashboardRows(rows: List<DashboardRow>): List<DashboardRow> =
    rows.sortedWith(
        compareBy<DashboardRow> { urgency(it.state) }
            .thenByDescending { it.updatedAt },
    )

/** What needs the user comes first, then what is moving; everything else is ordered by recency alone. */
private fun urgency(state: DashboardRowState): Int =
    when (state) {
        DashboardRowState.WAITING_FOR_YOU -> 0
        DashboardRowState.WORKING -> 1
        else -> 2
    }
