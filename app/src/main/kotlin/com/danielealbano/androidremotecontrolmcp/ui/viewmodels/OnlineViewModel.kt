package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.services.link.LinkSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The switch for staying connected unseen, and whether the system leaves the app alone when the phone dozes. */
@HiltViewModel
class OnlineViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val settings: LinkSettings,
    ) : ViewModel() {
        val background: StateFlow<Boolean> = settings.background.stateIn(viewModelScope, SharingStarted.Eagerly, true)
        private val _exempt = MutableStateFlow(isExempt())
        val exempt: StateFlow<Boolean> = _exempt.asStateFlow()

        fun setBackground(on: Boolean) {
            viewModelScope.launch { settings.setBackground(on) }
        }

        /** Asked again whenever the screen comes back: the user may just have answered the system's question. */
        fun refresh() {
            _exempt.value = isExempt()
        }

        private fun isExempt(): Boolean =
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
