package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.remote.ProviderChoices
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NewAgentUiState(
    /** True while the computer is being asked. */
    val asking: Boolean = true,
    val providers: List<RemoteProvider> = emptyList(),
    /** The provider whose models and modes are shown. */
    val provider: String = "",
    val choices: ProviderChoices = ProviderChoices(),
    /** Why the computer's own list is not shown; the user then types the names in. */
    val notice: String? = null,
)

/**
 * What a new agent can be started with, read from the computer when the dialog opens: which
 * programs it has, and each one's models and permission modes as they are today. Only when the
 * computer cannot say does the dialog fall back to typing the names.
 */
@HiltViewModel
class NewAgentViewModel
    @Inject
    constructor(
        private val computers: RemoteComputers,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _ui = MutableStateFlow(NewAgentUiState())
        val ui: StateFlow<NewAgentUiState> = _ui.asStateFlow()

        /** Asks the computer [computerId] what it can start, and about its first usable provider. */
        fun open(computerId: String) {
            _ui.value = NewAgentUiState()
            viewModelScope.launch(ioDispatcher) {
                val listed = computers.session(computerId)?.catalog?.providers()
                val providers = listed?.getOrNull().orEmpty()
                val first = providers.firstOrNull { it.available }?.id.orEmpty()
                val failed = listed?.exceptionOrNull()?.message ?: NO_PROVIDERS.takeIf { first.isEmpty() }
                _ui.update { it.copy(providers = providers, notice = failed?.let { why -> CANNOT_LIST + why }) }
                if (first.isEmpty()) _ui.update { it.copy(asking = false) } else pick(computerId, first)
            }
        }

        /** Shows the models and modes of [provider]. */
        fun pick(
            computerId: String,
            provider: String,
        ) {
            _ui.update { it.copy(provider = provider, asking = true, choices = ProviderChoices()) }
            viewModelScope.launch(ioDispatcher) {
                val asked = computers.session(computerId)?.catalog?.choices(provider)
                val failed = asked?.exceptionOrNull()?.message
                _ui.update { state ->
                    val current = state.provider == provider
                    val notice = failed?.let { CANNOT_LIST + it }
                    val choices = asked?.getOrNull() ?: ProviderChoices()
                    if (current) state.copy(asking = false, choices = choices, notice = notice) else state
                }
            }
        }

        private companion object {
            const val CANNOT_LIST = "没能从电脑读到可选项，可以手填："
            const val NO_PROVIDERS = "电脑上没有可用的代理程序"
        }
    }
