@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.remote.hooks.GoalRequest
import com.danielealbano.androidremotecontrolmcp.services.remote.hooks.GoalRun
import com.danielealbano.androidremotecontrolmcp.services.remote.hooks.GoalState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.GoalViewModel

private const val DEFAULT_ROUNDS = "10"
private const val DEFAULT_HOURS = "6"

/** 「目标模式」 for one agent: what it is working towards and how far it got, or a way to set a goal. */
@Composable
fun GoalBar(
    computerId: String,
    agentId: String,
    viewModel: GoalViewModel = hiltViewModel(),
) {
    LaunchedEffect(computerId, agentId) { viewModel.open(computerId, agentId) }
    val goal by viewModel.goal.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.remote_goal_title), style = MaterialTheme.typography.titleMedium)
            val run = goal
            if (run == null) {
                Text(stringResource(R.string.remote_goal_none), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(run.goal, style = MaterialTheme.typography.bodyMedium)
                Text(goalStatus(run), style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            GoalButtons(run, onSet = { asking = true }, onStop = viewModel::stop, onNudge = viewModel::nudge)
        }
    }
    if (asking) {
        GoalDialog(
            onDismiss = { asking = false },
            onStart = {
                viewModel.start(it)
                asking = false
            },
        )
    }
}

@Composable
private fun GoalButtons(
    run: GoalRun?,
    onSet: () -> Unit,
    onStop: () -> Unit,
    onNudge: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), Arrangement.End, Alignment.CenterVertically) {
        val paused = run?.state == GoalState.INTERRUPTED || run?.state == GoalState.FAILED
        if (paused) TextButton(onClick = onNudge) { Text(stringResource(R.string.remote_goal_nudge)) }
        if (run?.state?.active == true) {
            TextButton(onClick = onStop) { Text(stringResource(R.string.remote_goal_stop)) }
        } else {
            TextButton(onClick = onSet) { Text(stringResource(R.string.remote_goal_set)) }
        }
    }
}

@Composable
private fun GoalDialog(
    onDismiss: () -> Unit,
    onStart: (GoalRequest) -> Unit,
) {
    var goal by rememberSaveable { mutableStateOf("") }
    var criteria by rememberSaveable { mutableStateOf("") }
    var rounds by rememberSaveable { mutableStateOf(DEFAULT_ROUNDS) }
    var hours by rememberSaveable { mutableStateOf(DEFAULT_HOURS) }
    val request = GoalRequest(goal, criteria, rounds.toIntOrNull() ?: 0, hours.toIntOrNull() ?: 0)
    val ready = goal.isNotBlank() && criteria.isNotBlank() && request.maxRounds > 0 && request.hours > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_goal_set)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(goal, { goal = it }, label = { Text(stringResource(R.string.remote_goal_goal)) })
                val criteriaLabel = stringResource(R.string.remote_goal_criteria)
                OutlinedTextField(criteria, { criteria = it }, label = { Text(criteriaLabel) })
                val digits = KeyboardOptions(keyboardType = KeyboardType.Number)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val small = Modifier.weight(1f)
                    OutlinedTextField(
                        rounds,
                        { rounds = it.filter(Char::isDigit) },
                        small,
                        keyboardOptions = digits,
                        label = { Text(stringResource(R.string.remote_goal_rounds)) },
                    )
                    OutlinedTextField(
                        hours,
                        { hours = it.filter(Char::isDigit) },
                        small,
                        keyboardOptions = digits,
                        label = { Text(stringResource(R.string.remote_goal_hours)) },
                    )
                }
                Text(stringResource(R.string.remote_goal_judge_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            val start = stringResource(R.string.remote_goal_start)
            TextButton(onClick = { onStart(request) }, enabled = ready) { Text(start) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_goal_cancel)) } },
    )
}

/** One line for where [run] stands: 「第 2/10 轮 · 在做，还差：…」. Also used by the dashboard. */
@Composable
fun goalStatus(run: GoalRun): String {
    val round = stringResource(R.string.remote_goal_round, run.round, run.maxRounds)
    val missing = if (run.missing.isBlank()) "" else stringResource(R.string.remote_goal_missing, run.missing)
    return when (run.state) {
        GoalState.WORKING -> stringResource(R.string.remote_goal_working, round, missing)
        GoalState.JUDGING -> stringResource(R.string.remote_goal_judging, round)
        GoalState.INTERRUPTED -> stringResource(R.string.remote_goal_interrupted, round, run.note)
        GoalState.DONE -> stringResource(R.string.remote_goal_done, run.round)
        GoalState.STOPPED -> stringResource(R.string.remote_goal_stopped) + run.note.ifNotBlank { "：$it" }
        GoalState.OUT_OF_ROUNDS -> stringResource(R.string.remote_goal_out_of_rounds, run.missing)
        GoalState.OUT_OF_TIME -> stringResource(R.string.remote_goal_out_of_time)
        GoalState.FAILED -> stringResource(R.string.remote_goal_failed, run.note)
    }
}

private fun String.ifNotBlank(wrap: (String) -> String): String = if (isBlank()) "" else wrap(this)
