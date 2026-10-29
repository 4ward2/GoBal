@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkState
import com.danielealbano.androidremotecontrolmcp.services.remote.NewAgent
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteAgent
import com.danielealbano.androidremotecontrolmcp.ui.screens.remote.BindAsk
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ComputerRow
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.RemoteComputersViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.RemoteLines

/** Which of the page's dialogs is up. */
private sealed interface Asking {
    data object Scan : Asking

    data object Paste : Asking

    data object Manual : Asking

    data class NewAgent(
        val computer: ComputerRow,
    ) : Asking

    data class Unpair(
        val computer: ComputerRow,
    ) : Asking

    data class Relay(
        val computer: ComputerRow,
    ) : Asking
}

/**
 * The computers this phone is bound to: bind one (scan, paste a link, or type its address), see
 * whether it is reachable, and pick or start an agent on it to talk to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteComputersScreen(
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    pages: ComputerPages? = null,
    viewModel: RemoteComputersViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var asking by remember { mutableStateOf<Asking?>(null) }
    LaunchedEffect(viewModel) { viewModel.opened.collect { onOpenChat() } }
    GuideAsks(pages, onRequestCameraPermission) { asking = it }
    LaunchedEffect(ui.notice) {
        ui.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.noticeShown()
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_computers_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    val back = stringResource(R.string.settings_back)
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = back)
                }
            },
            windowInsets = WindowInsets(0),
        )
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "bind") {
                BindCard(
                    onScan = {
                        onRequestCameraPermission()
                        asking = Asking.Scan
                    },
                    onPaste = { asking = Asking.Paste },
                    onManual = { asking = Asking.Manual },
                    onGuide = pages?.let { it::guide },
                )
            }
            // Nothing is kept while nothing is bound: the switch appears with the first computer.
            if (ui.computers.isNotEmpty()) item(key = "online") { OnlineCard() }
            if (ui.computers.isNotEmpty()) item(key = "resume") { AutoResumeCard() }
            if (ui.vpnActive) item(key = "vpn") { Hint(stringResource(R.string.computers_vpn_on)) }
            items(ui.computers, key = { it.id }) { computer ->
                ComputerCard(
                    computer = computer,
                    onAgent = { viewModel.open(computer.id, it) },
                    onAsk = { asking = it },
                    onRefresh = { viewModel.refresh(computer.id) },
                    pages = pages,
                )
            }
        }
    }
    AskingDialogs(asking, viewModel) { asking = null }
}

/** Whichever dialog is up; each closes before it acts. */
@Composable
private fun AskingDialogs(
    asking: Asking?,
    viewModel: RemoteComputersViewModel,
    close: () -> Unit,
) {
    val then: (() -> Unit) -> Unit = { act ->
        close()
        act()
    }
    val pair: (String) -> Unit = { link -> then { viewModel.pair(link) } }
    when (asking) {
        null -> {
            Unit
        }

        Asking.Scan -> {
            ScanDialog(onFound = pair, onDismiss = close)
        }

        Asking.Paste -> {
            PasteLinkDialog(onDone = pair, onDismiss = close)
        }

        Asking.Manual -> {
            ManualAddressDialog(onDone = { typed -> pair(typed.link()) }, onDismiss = close)
        }

        is Asking.NewAgent -> {
            val start: (NewAgent) -> Unit = { spec -> then { viewModel.startAgent(asking.computer.id, spec) } }
            NewAgentDialog(asking.computer, onDone = start, onDismiss = close)
        }

        is Asking.Unpair -> {
            val unpair: () -> Unit = { then { viewModel.unpair(asking.computer.id) } }
            UnpairDialog(asking.computer, onConfirm = unpair, onDismiss = close)
        }

        is Asking.Relay -> {
            val set: (String) -> Unit = { relay -> then { viewModel.setRelay(asking.computer.id, relay) } }
            RelayDialog(asking.computer, onDone = set, onDismiss = close)
        }
    }
}

@Composable
private fun BindCard(
    onScan: () -> Unit,
    onPaste: () -> Unit,
    onManual: () -> Unit,
    onGuide: (() -> Unit)?,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.computers_bind_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.computers_bind_how),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val wide = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            Button(onClick = onScan, modifier = wide) { Text(stringResource(R.string.computers_scan)) }
            OutlinedButton(onClick = onPaste, modifier = wide) { Text(stringResource(R.string.computers_paste)) }
            OutlinedButton(onClick = onManual, modifier = wide) { Text(stringResource(R.string.computers_manual)) }
            if (onGuide != null) {
                TextButton(onClick = onGuide, modifier = wide) { Text(stringResource(R.string.remote_entry_guide)) }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ComputerCard(
    computer: ComputerRow,
    onAgent: (RemoteAgent) -> Unit,
    onAsk: (Asking) -> Unit,
    onRefresh: () -> Unit,
    pages: ComputerPages?,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(computer.name, style = MaterialTheme.typography.titleMedium)
                Hint(stringResource(R.string.computers_identity, computer.fingerprint, computer.route))
                LinkLine(computer)
            }
            // Above the agents: a computer can have dozens, and these must stay within reach.
            Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                val online = computer.link.state == LinkState.ONLINE
                TextButton(onClick = { onAsk(Asking.NewAgent(computer)) }, enabled = online) {
                    Text(stringResource(R.string.computers_new_agent))
                }
                TextButton(onClick = onRefresh, enabled = online) { Text(stringResource(R.string.computers_refresh)) }
                if (computer.route == RELAY_ROUTE) {
                    TextButton(onClick = { onAsk(Asking.Relay(computer)) }) {
                        Text(stringResource(R.string.computers_relay))
                    }
                }
                TextButton(onClick = { onAsk(Asking.Unpair(computer)) }) {
                    Text(stringResource(R.string.computers_unpair))
                }
            }
            val reachable = computer.link.state == LinkState.ONLINE
            if (reachable && pages != null) ComputerTools(computer.id, pages)
            computer.agents.forEach { agent ->
                val manage = pages?.let { { it.manage(computer.id, agent.id) } }
                AgentRow(agent, manage) { onAgent(agent) }
            }
        }
    }
}

private const val RELAY_ROUTE = "中继"

/** Where the link stands and, when it is down, why: never a silent failure. */
@Composable
private fun LinkLine(computer: ComputerRow) {
    val down = computer.link.state == LinkState.OFFLINE || computer.link.state == LinkState.REJECTED
    val color = if (down) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Text(RemoteLines.of(computer.link), style = MaterialTheme.typography.labelLarge, color = color)
    val why = computer.link.reason.takeIf { down && it.isNotBlank() }
    if (why != null) Text(why, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AgentRow(
    agent: RemoteAgent,
    onManage: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val waiting = if (agent.pending.isEmpty()) "" else stringResource(R.string.computers_agent_waiting)
    val detail = listOf(agent.provider, agent.status, waiting).filter { it.isNotBlank() }.joinToString(" · ")
    ListItem(
        headlineContent = {
            Text(agent.title.ifBlank { agent.provider }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = onManage?.let { { ManageAgentButton(it) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Opens the dialog the setup guide asked for when the user came back from it. */
@Composable
private fun GuideAsks(
    pages: ComputerPages?,
    onRequestCameraPermission: () -> Unit,
    onAsk: (Asking) -> Unit,
) {
    if (pages == null) return
    val wanted by pages.asks.collectAsStateWithLifecycle()
    LaunchedEffect(wanted) {
        val ask = ComputerPages.askOf(wanted) ?: return@LaunchedEffect
        pages.consumeAsk()
        if (ask == BindAsk.SCAN) onRequestCameraPermission()
        onAsk(
            when (ask) {
                BindAsk.SCAN -> Asking.Scan
                BindAsk.PASTE -> Asking.Paste
                BindAsk.MANUAL -> Asking.Manual
            },
        )
    }
}
