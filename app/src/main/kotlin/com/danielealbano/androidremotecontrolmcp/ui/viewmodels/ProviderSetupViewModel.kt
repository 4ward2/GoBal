package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.provider.DocsImport
import com.danielealbano.androidremotecontrolmcp.services.provider.KeyCheck
import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderDraft
import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderPresets
import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderSetup
import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderSetupLogic
import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderSetupRequests
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The "添加服务商" card for any input the local rules routed to model-service set-up. */
@OptIn(FlowPreview::class)
@HiltViewModel
class ProviderSetupViewModel
    @Inject
    constructor(
        private val requests: ProviderSetupRequests,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val pending: StateFlow<ProviderSetup?> = requests.pending

        private val _saved = MutableStateFlow<String?>(null)

        /** Name of the service just saved, for a one-off "已添加" message. */
        val saved: StateFlow<String?> = _saved.asStateFlow()

        init {
            // Once an address and a key are in the card and typing pauses, try the key by itself.
            viewModelScope.launch(ioDispatcher) {
                requests.pending
                    .map { setup -> setup?.takeIf { it.canCheck && it.check == KeyCheck.Idle }?.draft }
                    .distinctUntilChanged()
                    .debounce(CHECK_AFTER_MS)
                    .filter { it != null }
                    .collect { requests.check() }
            }
            // A documentation link in the sentence: read the page for the API address.
            viewModelScope.launch(ioDispatcher) {
                requests.pending
                    .map { setup -> setup?.takeIf { it.docs is DocsImport.Reading }?.draft?.sourceUrl }
                    .distinctUntilChanged()
                    .filter { it != null }
                    .collect { requests.importDocs() }
            }
        }

        fun edit(change: (ProviderDraft) -> ProviderDraft) = requests.edit(change)

        /** "自定义" is null: the address and name are then typed in by hand. */
        fun choosePreset(id: String?) = requests.edit { ProviderSetupLogic.withPreset(it, ProviderPresets.byId(id)) }

        fun chooseModel(model: String) = requests.choose { it.copy(model = model) }

        fun setUseForActive(on: Boolean) = requests.choose { it.copy(useForActive = on) }

        fun check() {
            viewModelScope.launch(ioDispatcher) { requests.check() }
        }

        fun confirm() {
            viewModelScope.launch(ioDispatcher) { requests.confirm()?.let { _saved.value = it } }
        }

        fun dismiss() = requests.dismiss()

        fun savedShown() {
            _saved.value = null
        }

        private companion object {
            const val CHECK_AFTER_MS = 700L
        }
    }
