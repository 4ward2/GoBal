@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.speech.LocalSttModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LocalSpeechUiState(
    val installed: Boolean = false,
    /** 0..1 while the recogniser is being downloaded, null otherwise. */
    val progress: Float? = null,
    val error: String? = null,
)

/** Installing the in-app speech recogniser (the one that needs no root). */
@HiltViewModel
class LocalSpeechViewModel
    @Inject
    constructor(
        private val model: LocalSttModel,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _ui = MutableStateFlow(LocalSpeechUiState(installed = model.installed()))
        val ui: StateFlow<LocalSpeechUiState> = _ui.asStateFlow()

        fun download() {
            if (_ui.value.progress != null) return
            _ui.update { it.copy(progress = 0f, error = null) }
            viewModelScope.launch(ioDispatcher) {
                val result = model.download { part -> _ui.update { it.copy(progress = part) } }
                val failure = result.exceptionOrNull()?.let { it.message ?: "下载失败" }
                _ui.update { LocalSpeechUiState(installed = model.installed(), error = failure) }
            }
        }
    }

/**
 * The recogniser inside the app: installed or not, and one button to fetch it. Phones without
 * the rooted setup dictate, wake and call through this one.
 */
@Composable
fun LocalSpeechCard(
    modifier: Modifier = Modifier,
    viewModel: LocalSpeechViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val progress = ui.progress
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.local_stt_title), style = MaterialTheme.typography.titleMedium)
            val state = if (ui.installed) R.string.local_stt_installed else R.string.local_stt_missing
            Text(
                stringResource(state, LocalSttModel.SIZE_MB),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val warning = MaterialTheme.colorScheme.error
            ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = warning) }
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            if (!ui.installed && progress == null) {
                Button(onClick = viewModel::download, modifier = Modifier.heightIn(min = BUTTON_HEIGHT)) {
                    Text(stringResource(R.string.local_stt_download))
                }
            }
        }
    }
}

private val BUTTON_HEIGHT = 48.dp
