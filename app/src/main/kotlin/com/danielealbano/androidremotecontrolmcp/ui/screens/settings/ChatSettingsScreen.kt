@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatHttpService
import com.danielealbano.androidremotecontrolmcp.services.chat.LoopbackStatus
import com.danielealbano.androidremotecontrolmcp.services.chat.RootHelper
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceLanguage
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AssistViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.NativeChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onRequestMicrophonePermission: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: NativeChatViewModel = hiltViewModel(),
    assistViewModel: AssistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val voiceLanguage by assistViewModel.voiceLanguage.collectAsStateWithLifecycle()
    val status = ui.status
    val helper = remember { RootHelper.present() }

    LaunchedEffect(Unit) {
        ChatHttpService.start(context.applicationContext)
        viewModel.refreshStatus()
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_chat_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                IconButton(onClick = { viewModel.refreshStatus() }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.native_chat_refresh))
                }
            },
            windowInsets = WindowInsets(0),
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { VoiceLanguagePicker(voiceLanguage, onPick = assistViewModel::updateVoiceLanguage) }
            item { LocalSpeechCard() }
            if (helper) helperRows(status)
        }
    }
}

/** What the root helper is doing: its model, its recogniser, its task watcher. */
private fun LazyListScope.helperRows(status: LoopbackStatus) {
    val provider = status.provider
    item {
        ListItem(
            headlineContent = { Text(stringResource(R.string.native_chat_provider)) },
            supportingContent = {
                Text(
                    if (provider.configured) {
                        listOf(provider.kind, provider.model, provider.authEnv)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    } else {
                        stringResource(R.string.native_chat_provider_missing)
                    },
                )
            },
        )
    }
    item {
        ListItem(
            headlineContent = { Text(stringResource(R.string.native_chat_endpoint)) },
            supportingContent = {
                Text(provider.endpoint.ifBlank { "—" })
            },
        )
    }
    item {
        ListItem(
            headlineContent = { Text(stringResource(R.string.native_chat_stt)) },
            supportingContent = {
                Text(
                    if (status.sttListening) {
                        stringResource(R.string.native_chat_stt_up)
                    } else {
                        stringResource(R.string.native_chat_stt_down)
                    },
                )
            },
        )
    }
    item {
        ListItem(
            headlineContent = { Text(stringResource(R.string.native_chat_agent_watch)) },
            supportingContent = {
                Text(
                    if (status.agentWatch) {
                        stringResource(R.string.native_chat_running)
                    } else {
                        stringResource(R.string.native_chat_stopped)
                    },
                )
            },
        )
    }
}

/** One recognizer language for every microphone: the chat and the power-button wake. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceLanguagePicker(
    current: VoiceLanguage,
    onPick: (VoiceLanguage) -> Unit,
) {
    ListItem(
        headlineContent = { Text("语音识别语言") },
        supportingContent = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceLanguage.entries.forEach { language ->
                    FilterChip(
                        selected = language == current,
                        onClick = { onPick(language) },
                        label = { Text(language.displayName) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        },
    )
}
