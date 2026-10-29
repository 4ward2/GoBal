package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.AppTier
import com.danielealbano.androidremotecontrolmcp.data.repository.TierSettings
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How much of the app is shown, for the screens that hide what the tier leaves out and the page that changes it. */
@HiltViewModel
class TierViewModel
    @Inject
    constructor(
        private val settings: TierSettings,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        /** Null until read: nothing is decided on a guess, so the simplest tier never flashes by for a developer. */
        val tier: StateFlow<AppTier?> = settings.tier.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        val developerUnlocked: StateFlow<Boolean> =
            settings.developerUnlocked.stateIn(viewModelScope, SharingStarted.Eagerly, false)

        fun set(tier: AppTier) {
            viewModelScope.launch(ioDispatcher) { settings.set(tier) }
        }
    }
