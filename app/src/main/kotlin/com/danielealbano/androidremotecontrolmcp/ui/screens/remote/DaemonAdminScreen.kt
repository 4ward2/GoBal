@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.DaemonAdminViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaemonAdminScreen(
    computerId: String,
    onBack: () -> Unit,
    viewModel: DaemonAdminViewModel = hiltViewModel(),
) {
    LaunchedEffect(computerId) {
        viewModel.open(computerId)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.remote_daemon_admin_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription =
                                stringResource(
                                    R.string.remote_daemon_admin_back,
                                ),
                        )
                    }
                },
            )
        },
    ) { padding ->
        DaemonAdminContent(
            state = state,
            viewModel = viewModel,
            modifier = Modifier.padding(padding),
        )
    }
}
