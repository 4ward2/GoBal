package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.assistant.AssistantLocalFirst
import com.danielealbano.androidremotecontrolmcp.services.assistant.LocalOutcome
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpLoopbackClient
import com.danielealbano.androidremotecontrolmcp.services.chat.VoskSpeechClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class DictationState(
    val listening: Boolean = false,
    val cancelHint: Boolean = false,
    /** Text recognised by the last hold-to-talk, waiting to be put into the draft. */
    val spoken: String? = null,
    val error: String? = null,
)

/**
 * Hold-to-talk for the chat composer. Spoken vault sentences are handled on the phone by the
 * local rules and never reach the draft (or a model); anything else lands in [DictationState.spoken]
 * for the chat screen to merge into the draft.
 */
@HiltViewModel
class DictationViewModel
    @Inject
    constructor(
        private val client: PhoneMcpLoopbackClient,
        private val vosk: VoskSpeechClient,
        private val localFirst: AssistantLocalFirst,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _state = MutableStateFlow(DictationState())
        val state: StateFlow<DictationState> = _state.asStateFlow()

        private val holdPressed = AtomicBoolean(false)
        private val holdCancelled = AtomicBoolean(false)

        fun begin() {
            if (_state.value.listening) return
            holdPressed.set(true)
            holdCancelled.set(false)
            viewModelScope.launch(ioDispatcher) {
                _state.update { it.copy(listening = true, cancelHint = false, error = null, spoken = null) }
                vosk.prepare { client.ensureStt() }
                val outcome =
                    runCatching {
                        vosk.listenHeld(shouldContinue = { holdPressed.get() }, isCancelled = { holdCancelled.get() })
                    }
                holdPressed.set(false)
                val cancelled =
                    holdCancelled.get() || outcome.exceptionOrNull()?.message == VoskSpeechClient.CANCELLED_MESSAGE
                if (cancelled) {
                    _state.update { it.copy(listening = false, cancelHint = false) }
                } else {
                    deliver(outcome)
                }
            }
        }

        private suspend fun deliver(outcome: Result<String>) {
            val spoken = outcome.getOrNull()?.trim().orEmpty()
            // A spoken vault sentence is confirmed right away and never shown in the draft box.
            val local = if (spoken.isEmpty()) null else localFirst.handle(spoken)
            val message =
                when {
                    local is LocalOutcome.Handled -> local.message
                    outcome.isFailure -> outcome.exceptionOrNull()?.message ?: "听写失败"
                    else -> null
                }
            val text = spoken.takeIf { local !is LocalOutcome.Handled && outcome.isSuccess && it.isNotEmpty() }
            _state.update { it.copy(listening = false, cancelHint = false, spoken = text, error = message) }
        }

        fun end(send: Boolean) {
            if (!send) holdCancelled.set(true)
            holdPressed.set(false)
        }

        fun setCancelHint(cancel: Boolean) {
            if (_state.value.listening) _state.update { it.copy(cancelHint = cancel) }
        }

        /** The chat screen took [DictationState.spoken] into the draft. */
        fun spokenConsumed() {
            _state.update { it.copy(spoken = null) }
        }

        fun dismissError() {
            _state.update { it.copy(error = null) }
        }
    }
