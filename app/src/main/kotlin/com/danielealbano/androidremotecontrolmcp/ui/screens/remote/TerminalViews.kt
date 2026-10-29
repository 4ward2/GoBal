@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.TerminalInfo
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.TerminalScreenState

private const val MAX_SCREEN_LINES = 2000
private const val TERMINAL_SCREEN_HEIGHT_DP = 300
private const val FONT_SIZE_MONOSPACE_SP = 10
private const val BUTTON_WEIGHT_SMALL = 0.2f
private const val PADDING_SMALL_DP = 4
private const val PADDING_MEDIUM_DP = 8
private const val PADDING_LARGE_DP = 12
private const val PADDING_EXTRA_LARGE_DP = 16
private const val ICON_SIZE_SMALL_DP = 18

internal data class TerminalListState(
    val terminals: List<TerminalInfo>,
    val isLoading: Boolean,
    val error: String?,
)

internal data class TerminalListActions(
    val onCreateTerminal: (String) -> Unit,
    val onSelectTerminal: (String) -> Unit,
    val onKillTerminal: (String) -> Unit,
    val onDismissError: () -> Unit,
)

internal data class TerminalSessionState(
    val terminal: TerminalInfo?,
    val screenState: TerminalScreenState?,
    val isLoading: Boolean,
    val error: String?,
)

internal data class TerminalSessionActions(
    val onInputChange: (String) -> Unit,
    val onSendInput: () -> Unit,
    val onKey: (String) -> Unit,
    val onKillTerminal: () -> Unit,
    val onDismissError: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
internal fun TerminalListViewContent(
    state: TerminalListState,
    actions: TerminalListActions,
) {
    // The list below scrolls by itself; a scrolling Column around a LazyColumn crashes.
    Column(modifier = Modifier.fillMaxSize()) {
        if (!state.error.isNullOrEmpty()) {
            ErrorBanner(state.error, onDismiss = actions.onDismissError)
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(PADDING_EXTRA_LARGE_DP.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Button(onClick = { actions.onCreateTerminal("terminal") }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE_SMALL_DP.dp),
                )
                Text(stringResource(R.string.remote_terminals_new))
            }
        }

        if (state.isLoading && state.terminals.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.remote_common_loading))
            }
        } else {
            LazyColumn {
                items(state.terminals, key = { it.id }) { terminal ->
                    TerminalListItem(
                        terminal = terminal,
                        onSelect = { actions.onSelectTerminal(terminal.id) },
                        onKill = { actions.onKillTerminal(terminal.id) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun TerminalSessionViewContent(
    state: TerminalSessionState,
    inputValue: String,
    actions: TerminalSessionActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (!state.error.isNullOrEmpty()) {
            ErrorBanner(state.error, onDismiss = actions.onDismissError)
        }

        if (state.terminal != null) {
            TerminalHeaderBar(
                terminal = state.terminal,
                onBack = actions.onBack,
                onKill = actions.onKillTerminal,
            )
        }

        TerminalScreenOutput(state.screenState, state.isLoading)
        TerminalInputControls(
            inputValue = inputValue,
            onInputChange = actions.onInputChange,
            onSendInput = actions.onSendInput,
            onKey = actions.onKey,
        )
    }
}

@Composable
private fun TerminalHeaderBar(
    terminal: TerminalInfo,
    onBack: () -> Unit,
    onKill: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(PADDING_MEDIUM_DP.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = terminal.name,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = terminal.cwd,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onBack) {
            Text(stringResource(R.string.remote_terminals_back_list))
        }
        IconButton(onClick = onKill) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.remote_common_delete),
            )
        }
    }
}

@Composable
private fun TerminalScreenOutput(
    screenState: TerminalScreenState?,
    isLoading: Boolean,
) {
    val scroll = rememberScrollState()
    // Like a real terminal, the view stays on the newest line as the screen changes.
    LaunchedEffect(screenState) { scroll.scrollTo(scroll.maxValue) }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(TERMINAL_SCREEN_HEIGHT_DP.dp)
                .background(Color.Black)
                .padding(PADDING_MEDIUM_DP.dp)
                .verticalScroll(scroll),
    ) {
        if (isLoading && screenState == null) {
            Text(
                stringResource(R.string.remote_common_loading),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
            )
        } else if (screenState != null) {
            val displayLines = screenState.lines.takeLast(MAX_SCREEN_LINES)
            Column {
                displayLines.forEach { line ->
                    Text(
                        text = line,
                        color = Color.Green,
                        fontFamily = FontFamily.Monospace,
                        fontSize = FONT_SIZE_MONOSPACE_SP.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalInputControls(
    inputValue: String,
    onInputChange: (String) -> Unit,
    onSendInput: () -> Unit,
    onKey: (String) -> Unit,
) {
    TerminalSpecialKeyRow(onKey)
    TerminalTextInputRow(inputValue, onInputChange, onSendInput)
}

@Composable
private fun TerminalSpecialKeyRow(onKey: (String) -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(PADDING_MEDIUM_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(PADDING_SMALL_DP.dp),
    ) {
        SpecialKeyButton(
            label = "Esc",
            onClick = { onKey("Escape") },
            modifier = Modifier.weight(BUTTON_WEIGHT_SMALL),
        )
        SpecialKeyButton(
            label = "Tab",
            onClick = { onKey("Tab") },
            modifier = Modifier.weight(BUTTON_WEIGHT_SMALL),
        )
        SpecialKeyButton(
            label = "C-C",
            onClick = { onKey("Ctrl-C") },
            modifier = Modifier.weight(BUTTON_WEIGHT_SMALL),
        )
        SpecialKeyButton(
            label = "↑",
            onClick = { onKey("Up") },
            modifier = Modifier.weight(BUTTON_WEIGHT_SMALL),
        )
        SpecialKeyButton(
            label = "↓",
            onClick = { onKey("Down") },
            modifier = Modifier.weight(BUTTON_WEIGHT_SMALL),
        )
    }
}

@Composable
private fun TerminalTextInputRow(
    inputValue: String,
    onInputChange: (String) -> Unit,
    onSendInput: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(PADDING_MEDIUM_DP.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = inputValue,
            onValueChange = onInputChange,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(end = PADDING_MEDIUM_DP.dp),
            placeholder = { Text(stringResource(R.string.remote_terminals_input_hint)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSendInput() }),
        )
        Button(onClick = onSendInput) {
            Text(stringResource(R.string.remote_terminals_send))
        }
    }
}

@Composable
internal fun TerminalListItem(
    terminal: TerminalInfo,
    onSelect: () -> Unit,
    onKill: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(PADDING_MEDIUM_DP.dp),
        shadowElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(PADDING_LARGE_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(end = PADDING_MEDIUM_DP.dp),
            ) {
                Text(
                    text = terminal.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
                Text(
                    text = terminal.cwd,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = onSelect,
                modifier = Modifier.padding(end = PADDING_MEDIUM_DP.dp),
            ) {
                Text(stringResource(R.string.remote_common_open))
            }
            IconButton(onClick = onKill) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.remote_common_delete),
                )
            }
        }
    }
}

@Composable
private fun SpecialKeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
            ),
    ) {
        Text(label, fontSize = 10.sp)
    }
}

@Composable
internal fun ErrorBanner(
    error: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(PADDING_EXTRA_LARGE_DP.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.remote_common_close),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
