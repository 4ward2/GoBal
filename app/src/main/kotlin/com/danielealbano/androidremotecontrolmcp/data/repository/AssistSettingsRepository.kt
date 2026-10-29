package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.AssistConfig
import com.danielealbano.androidremotecontrolmcp.data.model.AssistantProfile
import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceLanguage
import kotlinx.coroutines.flow.Flow

/** Persists the assistive-feature toggles ([AssistConfig]). */
interface AssistSettingsRepository {
    /** Observes the assistive-feature toggles; emits defaults for keys never written. */
    val assistConfig: Flow<AssistConfig>

    /** Updates the splash-ad auto-skip toggle. */
    suspend fun updateSplashSkipEnabled(enabled: Boolean)

    /** Updates the verification-code autofill toggle. */
    suspend fun updateOtpAutofillEnabled(enabled: Boolean)

    /** Observes the assistant's display name; [DEFAULT_ASSISTANT_NAME] until the user renames it. */
    val assistantName: Flow<String>

    /**
     * Renames the assistant. The name is trimmed and must be 1–[MAX_ASSISTANT_NAME_LENGTH] characters.
     *
     * @return the stored name, or a failure with an [IllegalArgumentException] when invalid.
     */
    suspend fun updateAssistantName(name: String): Result<String>

    /** Recognizer language for every microphone; the phone's locale until the user picks one. */
    val voiceLanguage: Flow<VoiceLanguage>

    suspend fun updateVoiceLanguage(language: VoiceLanguage)

    /** Language-model services configured under 设置 → 服务商, in the order they were added. */
    val providers: Flow<List<ModelProvider>>

    /** Adds the provider, or replaces the one with the same id. */
    suspend fun saveProvider(provider: ModelProvider)

    suspend fun deleteProvider(id: String)

    /** The assistant's conversation model; [ModelChoice.ON_DEVICE] until the user picks one. */
    val assistantModel: Flow<ModelChoice>

    suspend fun updateAssistantModel(choice: ModelChoice)

    /** The assistant's editable description and backstory, shown on its contact page. */
    val assistantProfile: Flow<AssistantProfile>

    suspend fun updateAssistantProfile(profile: AssistantProfile)

    companion object {
        const val DEFAULT_ASSISTANT_NAME = "小助手"
        const val MAX_ASSISTANT_NAME_LENGTH = 12
    }
}
