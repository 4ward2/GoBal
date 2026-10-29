package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRun
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** An app the user has answered "always" for, or switched off. */
data class AppGrant(
    val packageName: String,
    val label: String,
    val allowed: Boolean,
)

/** The assistant's task history and the apps it may work in, for the two settings pages. */
@HiltViewModel
class AgentSettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val state: AgentState,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        /** Finished phone tasks, newest first. */
        val history: StateFlow<List<AgentRun>> = state.runs.past

        private val _grants = MutableStateFlow<List<AppGrant>>(emptyList())
        val grants: StateFlow<List<AppGrant>> = _grants.asStateFlow()

        init {
            refresh()
        }

        fun clearHistory() {
            viewModelScope.launch(ioDispatcher) { state.runs.clearHistory() }
        }

        /** Always allow or never allow the app; null forgets the answer, so it is asked about again. */
        fun setAllowed(
            packageName: String,
            allowed: Boolean?,
        ) {
            viewModelScope.launch(ioDispatcher) {
                state.grants.set(packageName, allowed)
                load()
            }
        }

        private fun refresh() {
            viewModelScope.launch(ioDispatcher) { load() }
        }

        private fun load() {
            _grants.value =
                state.grants
                    .all()
                    .map { (packageName, allowed) -> AppGrant(packageName, labelOf(packageName), allowed) }
                    .sortedBy { it.label }
        }

        private fun labelOf(packageName: String): String =
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)
    }
