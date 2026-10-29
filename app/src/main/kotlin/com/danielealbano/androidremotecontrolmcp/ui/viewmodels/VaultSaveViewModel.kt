package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.vault.PendingVaultSave
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultSaveRequests
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The "存进保险箱？" confirmation shown for any input the local rules routed to the vault. */
@HiltViewModel
class VaultSaveViewModel
    @Inject
    constructor(
        private val requests: VaultSaveRequests,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val pending: StateFlow<PendingVaultSave?> = requests.pending

        private val _saved = MutableStateFlow<String?>(null)

        /** Label of the entry just saved, for a one-off "已存进保险箱" message. */
        val saved: StateFlow<String?> = _saved.asStateFlow()

        fun confirm() {
            viewModelScope.launch(ioDispatcher) { requests.confirm()?.let { _saved.value = it } }
        }

        fun dismiss() {
            requests.dismiss()
        }

        fun savedShown() {
            _saved.value = null
        }
    }
