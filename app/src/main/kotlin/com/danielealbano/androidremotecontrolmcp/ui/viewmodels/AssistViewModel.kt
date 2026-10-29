package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.AssistConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** Assistant profile (name) and assistive-feature toggles (splash-ad skip, verification-code autofill). */
@HiltViewModel
class AssistViewModel
    @Inject
    constructor(
        private val settingsRepository: AssistSettingsRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _assistConfig = MutableStateFlow(AssistConfig())
        val assistConfig: StateFlow<AssistConfig> = _assistConfig.asStateFlow()

        private val _assistantName = MutableStateFlow(AssistSettingsRepository.DEFAULT_ASSISTANT_NAME)
        val assistantName: StateFlow<String> = _assistantName.asStateFlow()

        private val _voiceLanguage = MutableStateFlow(VoiceLanguage.forLocale(Locale.getDefault()))
        val voiceLanguage: StateFlow<VoiceLanguage> = _voiceLanguage.asStateFlow()

        init {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.assistConfig.collect { _assistConfig.value = it }
            }
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.assistantName.collect { _assistantName.value = it }
            }
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.voiceLanguage.collect { _voiceLanguage.value = it }
            }
        }

        fun updateSplashSkipEnabled(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) { settingsRepository.updateSplashSkipEnabled(enabled) }
        }

        fun updateVoiceLanguage(language: VoiceLanguage) {
            viewModelScope.launch(ioDispatcher) { settingsRepository.updateVoiceLanguage(language) }
        }

        fun updateOtpAutofillEnabled(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) { settingsRepository.updateOtpAutofillEnabled(enabled) }
        }
    }
