@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.remote.hooks.InterruptionKind
import com.danielealbano.androidremotecontrolmcp.services.remote.hooks.ResumeEntry
import com.danielealbano.androidremotecontrolmcp.services.remote.hooks.ResumeOutcome
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.AutoResumeViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SHOWN = 6
private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L

/** 「中断了自动续跑」: off unless switched on; says exactly when it sends and when it does not. */
@Composable
internal fun AutoResumeCard(viewModel: AutoResumeViewModel = hiltViewModel()) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = MaterialTheme.typography.titleMedium
                Text(stringResource(R.string.remote_resume_title), Modifier.weight(1f), style = title)
                Switch(checked = enabled, onCheckedChange = viewModel::setEnabled)
            }
            val about = if (enabled) R.string.remote_resume_on else R.string.remote_resume_off
            Text(stringResource(about), style = MaterialTheme.typography.bodyMedium)
            if (enabled) {
                Text(stringResource(R.string.remote_resume_limits), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.remote_resume_overlap), style = MaterialTheme.typography.bodySmall)
            }
            if (log.isNotEmpty()) ResumeLog(log.take(SHOWN))
        }
    }
}

@Composable
private fun ResumeLog(entries: List<ResumeEntry>) {
    Text(stringResource(R.string.remote_resume_log), style = MaterialTheme.typography.labelLarge)
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    entries.forEach { entry ->
        val line =
            stringResource(
                R.string.remote_resume_entry,
                clock.format(Date(entry.at)),
                entry.computer,
                entry.agent,
                stringResource(kindLabel(entry.kind)) + "，" + outcomeText(entry),
            )
        Text(line, style = MaterialTheme.typography.bodySmall)
    }
}

private fun kindLabel(kind: InterruptionKind): Int =
    when (kind) {
        InterruptionKind.LIMIT -> R.string.remote_resume_kind_limit
        InterruptionKind.BUSY -> R.string.remote_resume_kind_busy
        InterruptionKind.RATE -> R.string.remote_resume_kind_rate
        InterruptionKind.CRASH -> R.string.remote_resume_kind_crash
    }

@Composable
private fun outcomeText(entry: ResumeEntry): String =
    when (entry.outcome) {
        ResumeOutcome.SCHEDULED -> stringResource(R.string.remote_resume_scheduled, duration(entry.waitMs))
        ResumeOutcome.RESUMED -> stringResource(R.string.remote_resume_resumed)
        ResumeOutcome.GAVE_UP -> stringResource(R.string.remote_resume_gave_up)
        ResumeOutcome.USER_TOOK_OVER -> stringResource(R.string.remote_resume_took_over)
        ResumeOutcome.NOT_SENT -> stringResource(R.string.remote_resume_not_sent)
    }

@Composable
private fun duration(ms: Long): String =
    when {
        ms < MINUTE_MS -> stringResource(R.string.remote_resume_seconds, (ms / SECOND_MS).toInt())
        ms < HOUR_MS -> stringResource(R.string.remote_resume_minutes, (ms / MINUTE_MS).toInt())
        else -> stringResource(R.string.remote_resume_hours, (ms / HOUR_MS).toInt(), (ms % HOUR_MS / MINUTE_MS).toInt())
    }
