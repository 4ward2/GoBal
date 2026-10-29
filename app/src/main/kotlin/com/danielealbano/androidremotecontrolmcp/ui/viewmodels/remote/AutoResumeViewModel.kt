package com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.services.remote.hooks.AutoResume
import com.danielealbano.androidremotecontrolmcp.services.remote.hooks.ResumeEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The switch for 「中断了自动续跑」 and what it did lately. */
@HiltViewModel
class AutoResumeViewModel
    @Inject
    constructor(
        private val resume: AutoResume,
    ) : ViewModel() {
        val enabled: StateFlow<Boolean> = resume.enabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
        val log: StateFlow<List<ResumeEntry>> = resume.log

        fun setEnabled(on: Boolean) {
            viewModelScope.launch { resume.setEnabled(on) }
        }
    }
