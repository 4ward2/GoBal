@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRun
import com.danielealbano.androidremotecontrolmcp.services.agent.PlanItem
import com.danielealbano.androidremotecontrolmcp.services.agent.PlanState
import com.danielealbano.androidremotecontrolmcp.services.agent.TreeLine

/**
 * What an opened task card shows: the chain of the plan (what was done before the plan changed,
 * then each step with how it stands and what it achieved), the map of what was searched (only
 * for tasks that kept one), the latest actions, and what the user added.
 */
@Composable
internal fun TaskDetails(run: AgentRun) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        PlanSection(run)
        if (run.tree.isNotEmpty()) TreeSection(run.tree)
        if (run.steps.isNotEmpty()) ActionsSection(run)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun PlanSection(run: AgentRun) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(stringResource(R.string.chat_task_plan, TaskCardText.planCount(run)))
        if (run.plan.isEmpty()) Faint(stringResource(R.string.chat_task_plan_none))
        val earlier = run.plan.filter { it.earlier }
        if (earlier.isNotEmpty()) {
            Faint(stringResource(R.string.chat_task_plan_earlier))
            earlier.forEach { PlanStep(it) }
        }
        run.plan.filterNot { it.earlier }.forEach { PlanStep(it) }
    }
}

/**
 * A step of the plan with how it stands, drawn rather than written: a lit check for done, a lit
 * ring for the step in hand, a dim empty ring for what is left. Steps done before the plan was
 * changed keep their check, dimmed.
 */
@Composable
private fun PlanStep(item: PlanItem) {
    val scheme = MaterialTheme.colorScheme
    val todo = item.state == PlanState.TODO
    val (icon, tint) =
        when (item.state) {
            PlanState.DONE -> Icons.Filled.CheckCircle to scheme.primary.copy(alpha = if (item.earlier) DIM else 1f)
            PlanState.NOW -> Icons.Filled.RadioButtonChecked to scheme.primary
            PlanState.TODO -> Icons.Outlined.RadioButtonUnchecked to scheme.onSurfaceVariant.copy(alpha = DIM)
        }
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(MARK))
        Spacer(Modifier.width(MARK_GAP))
        Text(
            item.text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (item.state == PlanState.NOW) FontWeight.SemiBold else FontWeight.Normal,
            color = if (todo) scheme.onSurfaceVariant else scheme.onSurface,
        )
    }
    if (item.outcome.isNotEmpty()) Faint(item.outcome, Modifier.padding(start = MARK + MARK_GAP))
}

@Composable
private fun TreeSection(tree: List<TreeLine>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionTitle(stringResource(R.string.chat_task_tree))
        tree.forEach { line ->
            val inset = Modifier.padding(start = INDENT * line.depth)
            val mark = if (line.mark.isEmpty()) "" else "（${line.mark}）"
            Text(line.name + mark, style = MaterialTheme.typography.bodyMedium, modifier = inset)
            line.facts.forEach { Faint("· $it", Modifier.padding(start = INDENT * (line.depth + 1))) }
        }
    }
}

@Composable
private fun ActionsSection(run: AgentRun) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionTitle(stringResource(R.string.chat_task_actions))
        val hidden = TaskCardSteps.hidden(run)
        if (hidden > 0) Faint(stringResource(R.string.chat_task_more_steps, hidden))
        TaskCardSteps.lines(run).forEach { Faint(it) }
        run.notes.forEach { Faint(stringResource(R.string.chat_task_note, it)) }
    }
}

@Composable
private fun Faint(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

private val INDENT = 14.dp
private val MARK = 18.dp
private val MARK_GAP = 8.dp
private const val DIM = 0.45f
