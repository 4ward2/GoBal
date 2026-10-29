@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.TerminalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    computerId: String,
    cwd: String,
    onBack: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val state = viewModel.state.collectAsState()
    val inputValue = remember { mutableStateOf("") }

    LaunchedEffect(computerId, cwd) {
        viewModel.open(computerId, cwd)
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
    ) {
        TerminalTopBar(onBack)

        val uiState = state.value
        val selected = uiState.selectedTerminalId
        if (selected == null) {
            TerminalListViewContent(
                state = TerminalListState(uiState.terminals, uiState.isLoading, uiState.error),
                actions =
                    TerminalListActions(
                        onCreateTerminal = { name -> viewModel.createTerminal(cwd, name.ifBlank { null }) },
                        onSelectTerminal = { terminalId -> viewModel.selectTerminal(terminalId) },
                        onKillTerminal = { terminalId -> viewModel.killTerminal(terminalId) },
                        onDismissError = { viewModel.clearError() },
                    ),
            )
        } else {
            TerminalSessionViewContent(
                state =
                    TerminalSessionState(
                        terminal = uiState.terminals.find { it.id == selected },
                        screenState = uiState.screenState,
                        isLoading = uiState.isLoading,
                        error = uiState.error,
                    ),
                inputValue = inputValue.value,
                actions =
                    TerminalSessionActions(
                        onInputChange = { inputValue.value = it },
                        onSendInput = {
                            viewModel.input.sendText(inputValue.value + "\r")
                            inputValue.value = ""
                        },
                        onKey = { key -> viewModel.input.sendKey(key) },
                        onKillTerminal = { viewModel.killTerminal(selected) },
                        onDismissError = { viewModel.clearError() },
                        onBack = { viewModel.deselectTerminal() },
                    ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalTopBar(onBack: () -> Unit) {
    TopAppBar(
        windowInsets = WindowInsets(0),
        title = { Text(stringResource(R.string.remote_terminals_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.remote_common_back),
                )
            }
        },
    )
}
