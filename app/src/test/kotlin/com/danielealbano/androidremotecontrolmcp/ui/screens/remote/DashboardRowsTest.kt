package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRun
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentStep
import com.danielealbano.androidremotecontrolmcp.services.agent.RunStatus
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkState
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteAgent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DashboardRowsTest {
    @Test
    fun `agentRunToDashboardRow maps RUNNING to WORKING`() {
        val run =
            AgentRun(
                id = "1",
                goal = "测试任务",
                status = RunStatus.RUNNING,
                steps = listOf(AgentStep("第一步")),
            )
        val row = agentRunToDashboardRow(run)
        assertEquals(DashboardRowState.WORKING, row.state)
    }

    @Test
    fun `agentRunToDashboardRow maps WAITING_APPROVAL to WAITING_FOR_YOU`() {
        val run =
            AgentRun(
                id = "1",
                goal = "测试任务",
                status = RunStatus.WAITING_APPROVAL,
            )
        val row = agentRunToDashboardRow(run)
        assertEquals(DashboardRowState.WAITING_FOR_YOU, row.state)
    }

    @Test
    fun `agentRunToDashboardRow maps DONE to IDLE`() {
        val run =
            AgentRun(
                id = "1",
                goal = "测试任务",
                status = RunStatus.DONE,
                steps = listOf(AgentStep("第一步"), AgentStep("第二步")),
            )
        val row = agentRunToDashboardRow(run)
        assertEquals(DashboardRowState.IDLE, row.state)
    }

    @Test
    fun `agentRunToDashboardRow maps FAILED to FAILED`() {
        val run =
            AgentRun(
                id = "1",
                goal = "测试任务",
                status = RunStatus.FAILED,
            )
        val row = agentRunToDashboardRow(run)
        assertEquals(DashboardRowState.FAILED, row.state)
    }

    @Test
    fun `agentRunToDashboardRow maps HANDED_OVER to WAITING_FOR_YOU`() {
        val run =
            AgentRun(
                id = "1",
                goal = "测试任务",
                status = RunStatus.HANDED_OVER,
            )
        val row = agentRunToDashboardRow(run)
        assertEquals(DashboardRowState.WAITING_FOR_YOU, row.state)
    }

    @Test
    fun `remoteAgentToDashboardRow maps running agent to WORKING`() {
        val agent =
            RemoteAgent(
                id = "1",
                title = "Claude 分析",
                provider = "claude",
                model = "claude-opus",
                cwd = "/",
                status = "running",
            )
        val row = remoteAgentToDashboardRow("电脑1", agent, LinkState.ONLINE, "comp1")
        assertEquals(DashboardRowState.WORKING, row.state)
    }

    @Test
    fun `remoteAgentToDashboardRow maps idle agent to IDLE`() {
        val agent =
            RemoteAgent(
                id = "1",
                title = "Codex 测试",
                provider = "codex",
                model = "gpt-6",
                cwd = "/",
                status = "idle",
            )
        val row = remoteAgentToDashboardRow("电脑1", agent, LinkState.ONLINE, "comp1")
        assertEquals(DashboardRowState.IDLE, row.state)
    }

    @Test
    fun `remoteAgentToDashboardRow maps agent with pending approval to WAITING_FOR_YOU`() {
        val agent =
            RemoteAgent(
                id = "1",
                title = "需要批准",
                provider = "claude",
                model = "claude-opus",
                cwd = "/",
                status = "idle",
                pending = listOf(),
            )
        val row = remoteAgentToDashboardRow("电脑1", agent, LinkState.ONLINE, "comp1")
        assertEquals(DashboardRowState.IDLE, row.state)
    }

    @Test
    fun `remoteAgentToDashboardRow maps error agent to FAILED`() {
        val agent =
            RemoteAgent(
                id = "1",
                title = "出错的代理",
                provider = "claude",
                model = "claude-opus",
                cwd = "/",
                status = "error",
            )
        val row = remoteAgentToDashboardRow("电脑1", agent, LinkState.ONLINE, "comp1")
        assertEquals(DashboardRowState.FAILED, row.state)
    }

    @Test
    fun `remoteAgentToDashboardRow maps offline computer to OFFLINE`() {
        val agent =
            RemoteAgent(
                id = "1",
                title = "代理",
                provider = "claude",
                model = "claude-opus",
                cwd = "/",
                status = "idle",
            )
        val row = remoteAgentToDashboardRow("电脑1", agent, LinkState.OFFLINE, "comp1")
        assertEquals(DashboardRowState.OFFLINE, row.state)
    }

    @Test
    fun `sortDashboardRows sorts by state then by recency`() {
        val rows =
            listOf(
                DashboardRow("手机", "任务3", "", DashboardRowState.IDLE, 100),
                DashboardRow("电脑1", "代理1", "", DashboardRowState.WORKING, 50),
                DashboardRow("电脑2", "代理2", "", DashboardRowState.WAITING_FOR_YOU, 30),
                DashboardRow("电脑3", "代理3", "", DashboardRowState.FAILED, 20),
            )
        val sorted = sortDashboardRows(rows)

        assertEquals(DashboardRowState.WAITING_FOR_YOU, sorted[0].state)
        assertEquals("代理2", sorted[0].title)

        assertEquals(DashboardRowState.WORKING, sorted[1].state)
        assertEquals("代理1", sorted[1].title)

        assertEquals(DashboardRowState.IDLE, sorted[2].state)
        assertEquals("任务3", sorted[2].title)

        assertEquals(DashboardRowState.FAILED, sorted[3].state)
        assertEquals("代理3", sorted[3].title)
    }

    @Test
    fun `sortDashboardRows within same state sorts by recency descending`() {
        val rows =
            listOf(
                DashboardRow("电脑1", "代理1", "", DashboardRowState.IDLE, 100),
                DashboardRow("电脑2", "代理2", "", DashboardRowState.IDLE, 200),
                DashboardRow("电脑3", "代理3", "", DashboardRowState.IDLE, 50),
            )
        val sorted = sortDashboardRows(rows)

        assertEquals("代理2", sorted[0].title)
        assertEquals(200L, sorted[0].updatedAt)

        assertEquals("代理1", sorted[1].title)
        assertEquals(100L, sorted[1].updatedAt)

        assertEquals("代理3", sorted[2].title)
        assertEquals(50L, sorted[2].updatedAt)
    }

    @Test
    fun `agentRunToDashboardRow truncates long titles`() {
        val longGoal = "a".repeat(100)
        val run =
            AgentRun(
                id = "1",
                goal = longGoal,
                status = RunStatus.RUNNING,
            )
        val row = agentRunToDashboardRow(run)
        assertTrue(row.title.length <= 60)
    }

    @Test
    fun `agentRunStatus formats running status correctly`() {
        val run =
            AgentRun(
                id = "1",
                goal = "测试",
                status = RunStatus.RUNNING,
                steps = listOf(AgentStep("第一步"), AgentStep("第二步")),
            )
        val row = agentRunToDashboardRow(run)
        assertEquals("第 3 步", row.status)
    }

    @Test
    fun `agentRunStatus formats done status correctly`() {
        val run =
            AgentRun(
                id = "1",
                goal = "测试",
                status = RunStatus.DONE,
                steps = listOf(AgentStep("第一步")),
            )
        val row = agentRunToDashboardRow(run)
        assertEquals("1 步 · 完成", row.status)
    }

    @Test
    fun `remoteAgentToDashboardRow carries exact agent id and computer id`() {
        val agent =
            RemoteAgent(
                id = "agent-uuid-12345",
                title = "Claude · 文件分析",
                provider = "claude",
                model = "claude-opus",
                cwd = "/home/user",
                status = "idle",
            )
        val row =
            remoteAgentToDashboardRow(
                computerName = "我的电脑",
                agent = agent,
                linkState = LinkState.ONLINE,
                computerId = "computer-uuid-67890",
            )
        assertEquals("computer-uuid-67890", row.computerId)
        assertEquals("agent-uuid-12345", row.agentId)
    }

    @Test
    fun `agentRunToDashboardRow has null ids for phone task`() {
        val run =
            AgentRun(
                id = "1",
                goal = "查看美团拼好饭",
                status = RunStatus.DONE,
            )
        val row = agentRunToDashboardRow(run)
        assertEquals(null, row.computerId)
        assertEquals(null, row.agentId)
    }
}
