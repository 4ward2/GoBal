@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.remote.NewAgent
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ComputerRow
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.NewAgentUiState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.NewAgentViewModel

/** A choice among names the computer gave: tap one, or tap it again for "whatever the program's default is". */
private class Choice(
    val label: Int,
    /** id to what the user reads. */
    val options: List<Pair<String, String>>,
    val picked: String,
    val onPick: (String) -> Unit,
)

/**
 * Starts an agent on a computer. Which programs it has, and each one's models and permission
 * modes, are read from the computer when this opens; nothing is a list shipped with the app.
 * The names are typed in only when the computer could not be asked.
 */
@Composable
internal fun NewAgentDialog(
    computer: ComputerRow,
    onDone: (NewAgent) -> Unit,
    onDismiss: () -> Unit,
    viewModel: NewAgentViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(computer.id) { viewModel.open(computer.id) }
    var cwd by rememberSaveable {
        mutableStateOf(
            computer.agents
                .firstOrNull()
                ?.cwd
                .orEmpty(),
        )
    }
    var title by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable(ui.provider) { mutableStateOf("") }
    var mode by rememberSaveable(ui.provider) { mutableStateOf("") }
    val ready = cwd.isNotBlank() && ui.provider.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.computers_new_agent)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
                Providers(ui) { viewModel.pick(computer.id, it) }
                Models(ui, model) { model = it }
                Modes(ui, mode) { mode = it }
                Typed(cwd, R.string.computers_cwd) { cwd = it }
                Typed(title, R.string.computers_agent_title) { title = it }
            }
        },
        confirmButton = {
            val spec = NewAgent(ui.provider, cwd.trim(), model.trim(), title.trim(), mode.trim())
            TextButton(onClick = { onDone(spec) }, enabled = ready) { Text(stringResource(R.string.computers_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_rename_cancel)) } },
    )
}

@Composable
private fun Providers(
    ui: NewAgentUiState,
    onPick: (String) -> Unit,
) {
    if (ui.asking) LinearProgressIndicator()
    ui.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    // A program that is installed but switched off is shown, greyed: it explains why it cannot be picked.
    val options = ui.providers.map { it.id to it.id }
    val usable = ui.providers.filter { it.available }.map { it.id }
    Chips(Choice(R.string.computers_provider, options, ui.provider, onPick), usable)
}

@Composable
private fun Models(
    ui: NewAgentUiState,
    picked: String,
    onPick: (String) -> Unit,
) {
    val models = ui.choices.models
    if (models.isEmpty()) {
        if (!ui.asking) Typed(picked, R.string.computers_model, onPick)
    } else {
        Chips(Choice(R.string.computers_model_pick, models.map { it.id to it.label }, picked, onPick))
        val shown = models.firstOrNull { it.id == picked } ?: models.firstOrNull { it.isDefault }
        shown?.description?.takeIf { it.isNotBlank() }?.let { Faint(it) }
    }
}

@Composable
private fun Modes(
    ui: NewAgentUiState,
    picked: String,
    onPick: (String) -> Unit,
) {
    val modes = ui.choices.modes
    if (modes.isEmpty()) {
        if (!ui.asking) Typed(picked, R.string.computers_mode, onPick)
    } else {
        Chips(Choice(R.string.computers_mode_pick, modes.map { it.id to it.label }, picked, onPick))
        modes
            .firstOrNull { it.id == picked }
            ?.description
            ?.takeIf { it.isNotBlank() }
            ?.let { Faint(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(
    choice: Choice,
    usable: List<String> = choice.options.map { it.first },
) {
    if (choice.options.isEmpty()) return
    Faint(stringResource(choice.label))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choice.options.forEach { (id, label) ->
            val selected = choice.picked == id
            FilterChip(
                selected = selected,
                enabled = id in usable,
                // Tapping the picked one again goes back to the program's own default.
                onClick = { choice.onPick(if (selected && choice.label != R.string.computers_provider) "" else id) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun Faint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Typed(
    value: String,
    label: Int,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(value, onChange, label = { Text(stringResource(label)) }, singleLine = true)
}
