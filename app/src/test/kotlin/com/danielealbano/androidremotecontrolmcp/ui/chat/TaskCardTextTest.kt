package com.danielealbano.androidremotecontrolmcp.ui.chat

import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRun
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentStep
import com.danielealbano.androidremotecontrolmcp.services.agent.PlanItem
import com.danielealbano.androidremotecontrolmcp.services.agent.PlanState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Task card text")
class TaskCardTextTest {
    private val plan =
        listOf(
            PlanItem("旧的一步", PlanState.DONE, "做完了", earlier = true),
            PlanItem("进入饮品甜点", PlanState.DONE, "10 样"),
            PlanItem("逐个点开", PlanState.NOW),
            PlanItem("汇总"),
        )

    @Test
    fun `the folded card says which step of the plan it is on and the latest action`() {
        val run = AgentRun("t1", "整理饮品甜点", plan = plan, steps = listOf(AgentStep("点「冰粉」")))
        assertEquals("第2/3步 · 点「冰粉」", TaskCardText.now(run))
        assertEquals("1/3", TaskCardText.planCount(run))
    }

    @Test
    fun `before a plan the latest action is shown, and with nothing done yet there is nothing to say`() {
        assertEquals("看屏幕", TaskCardText.now(AgentRun("t1", "x", steps = listOf(AgentStep("看屏幕")))))
        assertNull(TaskCardText.now(AgentRun("t1", "x")))
    }
}
