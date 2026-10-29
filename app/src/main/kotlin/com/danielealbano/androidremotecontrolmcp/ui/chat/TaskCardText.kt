package com.danielealbano.androidremotecontrolmcp.ui.chat

import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRun
import com.danielealbano.androidremotecontrolmcp.services.agent.PlanState

/** The words of a task card that come from the task's record. Pure. */
object TaskCardText {
    /**
     * The one line a folded card shows while the task runs: which step of the plan it is on and
     * the latest thing done ("第2/4步 · 点「饮品甜点」"); null while it is still thinking the
     * task through and has done nothing yet.
     */
    fun now(run: AgentRun): String? {
        val current = run.plan.filterNot { it.earlier }
        val at = current.indexOfFirst { it.state == PlanState.NOW }
        val position = if (at >= 0) "第${at + 1}/${current.size}步" else null
        val last = run.steps.lastOrNull()?.label
        return listOfNotNull(position, last).joinToString(" · ").ifEmpty { null }
    }

    /** How many steps of the current plan are done, of how many: "2/4". */
    fun planCount(run: AgentRun): String {
        val current = run.plan.filterNot { it.earlier }
        return "${current.count { it.state == PlanState.DONE }}/${current.size}"
    }
}
