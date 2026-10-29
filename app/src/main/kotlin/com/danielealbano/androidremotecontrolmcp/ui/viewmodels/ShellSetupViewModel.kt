package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.services.shell.PairPhase
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellBridge
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellChecklist
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellPairing
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellState
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Whether a command really ran as the shell user, the last time it was tried. */
enum class ShellCheck {
    UNKNOWN,
    CHECKING,
    WORKS,
    FAILED,
}

data class ShellSetupUi(
    val state: ShellState = ShellState(),
    val step: ShellStep = ShellStep.NOTIFICATIONS,
    val phase: PairPhase = PairPhase.IDLE,
    val check: ShellCheck = ShellCheck.UNKNOWN,
)

@HiltViewModel
class ShellSetupViewModel
    @Inject
    constructor(
        private val bridge: ShellBridge,
    ) : ViewModel() {
        private val _ui = MutableStateFlow(ShellSetupUi())
        val ui: StateFlow<ShellSetupUi> = _ui

        init {
            viewModelScope.launch {
                ShellPairing.phase.collect { phase ->
                    _ui.update { it.copy(phase = phase) }
                    if (phase == PairPhase.DONE) refresh()
                }
            }
        }

        /** Looks at the phone again: the user changes all of this in the system's settings. */
        fun refresh() {
            val state = bridge.state()
            val step = ShellChecklist.next(state)
            _ui.update { it.copy(state = state, step = step) }
            if (step == ShellStep.READY && _ui.value.check == ShellCheck.UNKNOWN) verify()
        }

        /** Runs `id` through the bridge: the only proof that the whole chain works. */
        fun verify() {
            if (_ui.value.check == ShellCheck.CHECKING) return
            _ui.update { it.copy(check = ShellCheck.CHECKING) }
            viewModelScope.launch {
                val works = bridge.run("id").getOrNull()?.let(ShellChecklist::isShell) == true
                _ui.update { it.copy(check = if (works) ShellCheck.WORKS else ShellCheck.FAILED) }
                // A refused key clears the pairing mark; show the step that follows from it.
                val state = bridge.state()
                _ui.update { it.copy(state = state, step = ShellChecklist.next(state)) }
            }
        }
    }
