@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Help
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.DaemonConfigView
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.ProviderHealth
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.ProviderStatus
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.DaemonAdminUiState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote.DaemonAdminViewModel

private const val ICON_SIZE_SMALL_DP = 20
private const val ICON_SIZE_MEDIUM_DP = 24
private const val ICON_SIZE_LARGE_DP = 48
private const val PADDING_HORIZONTAL_DP = 16
private const val PADDING_VERTICAL_DP = 8
private const val PADDING_TOP_DP = 8

@Composable
internal fun DaemonAdminContent(
    state: DaemonAdminUiState,
    viewModel: DaemonAdminViewModel,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
    ) {
        when {
            state.isLoading && state.config == null -> LoadingState()
            state.error.isNotEmpty() && state.config == null -> ErrorState(state.error)
            else -> ContentState(state, viewModel)
        }
    }
}

@Composable
private fun BoxScope.LoadingState() {
    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
}

@Composable
private fun BoxScope.ErrorState(error: String) {
    Column(
        modifier =
            Modifier
                .align(Alignment.Center)
                .padding(PADDING_HORIZONTAL_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE_LARGE_DP.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = PADDING_TOP_DP.dp),
        )
    }
}

@Composable
private fun BoxScope.ContentState(
    state: DaemonAdminUiState,
    viewModel: DaemonAdminViewModel,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            if (state.config != null) {
                DaemonInfoCard(state.config!!)
            }
        }

        item {
            Column(modifier = Modifier.padding(PADDING_HORIZONTAL_DP.dp)) {
                Text(
                    stringResource(R.string.remote_daemon_admin_providers),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        items(state.providers) { provider ->
            ProviderListItem(
                provider = provider,
                health = state.diagnostics[provider.id],
                onDiagnose = { viewModel.diagnoseProvider(provider.id) },
            )
        }

        item {
            ActionButtonsSection(
                isLoading = state.isLoading,
                isRestarting = state.isRestarting,
                error = state.error,
                onRefresh = { viewModel.refreshProviders() },
                onRestart = { viewModel.restartServer() },
            )
        }
    }
}

@Composable
private fun ActionButtonsSection(
    isLoading: Boolean,
    isRestarting: Boolean,
    error: String,
    onRefresh: () -> Unit,
    onRestart: () -> Unit,
) {
    Column(modifier = Modifier.padding(PADDING_HORIZONTAL_DP.dp)) {
        Button(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(ICON_SIZE_SMALL_DP.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.remote_daemon_admin_refresh_providers))
            }
        }

        Button(
            onClick = onRestart,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = PADDING_TOP_DP.dp),
            enabled = !isRestarting,
        ) {
            if (isRestarting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(ICON_SIZE_SMALL_DP.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.remote_daemon_admin_restart_server))
            }
        }

        if (error.isNotEmpty()) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = PADDING_TOP_DP.dp),
            )
        }
    }
}

@Composable
internal fun DaemonInfoCard(config: DaemonConfigView) {
    ListItem(
        headlineContent = {
            Text(stringResource(R.string.remote_daemon_admin_relay_status))
        },
        supportingContent = {
            val relayText =
                if (config.relayEnabled) {
                    stringResource(R.string.remote_daemon_admin_relay_on)
                } else {
                    stringResource(R.string.remote_daemon_admin_relay_off)
                }
            Text(relayText)
        },
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

@Composable
internal fun ProviderListItem(
    provider: ProviderStatus,
    health: ProviderHealth?,
    onDiagnose: () -> Unit,
) {
    // The light is the daemon's own readiness; a diagnostic is a report to read, not a verdict.
    val icon = if (provider.ready) Icons.Default.CheckCircle else Icons.Default.Error
    val iconTint = if (provider.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val stateLabel =
        stringResource(
            if (provider.ready) R.string.remote_daemon_admin_ready else R.string.remote_daemon_admin_not_ready,
        )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = PADDING_HORIZONTAL_DP.dp,
                    vertical = PADDING_VERTICAL_DP.dp,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = stateLabel,
            modifier = Modifier.size(ICON_SIZE_MEDIUM_DP.dp),
            tint = iconTint,
        )

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
        ) {
            Text(provider.id, style = MaterialTheme.typography.bodyMedium)
            if (health != null) {
                Text(
                    health.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        androidx.compose.material3.TextButton(onClick = onDiagnose) {
            Text(stringResource(R.string.remote_daemon_admin_diagnose))
        }
    }
}
