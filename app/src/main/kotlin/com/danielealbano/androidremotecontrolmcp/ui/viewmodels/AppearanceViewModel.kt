package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.Appearance
import com.danielealbano.androidremotecontrolmcp.data.repository.AppearanceSettings
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How the app looks, for the activity's theme and for the page that changes it. */
@HiltViewModel
class AppearanceViewModel
    @Inject
    constructor(
        private val settings: AppearanceSettings,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val appearance: StateFlow<Appearance> =
            settings.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, Appearance())

        fun update(appearance: Appearance) {
            viewModelScope.launch(ioDispatcher) { settings.update(appearance) }
        }
    }
