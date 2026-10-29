package com.danielealbano.androidremotecontrolmcp.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.AssistConfig
import com.danielealbano.androidremotecontrolmcp.data.model.AssistantProfile
import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AssistSettingsRepository] stored in the shared `settings` Preferences DataStore, under keys of
 * its own, so the assistive toggles and the assistant's name do not grow [SettingsRepositoryImpl] further.
 */
@Singleton
class AssistSettingsRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : AssistSettingsRepository {
        override val assistConfig: Flow<AssistConfig> =
            dataStore.data.map { prefs ->
                val defaults = AssistConfig()
                AssistConfig(
                    splashSkipEnabled = prefs[SPLASH_SKIP_ENABLED_KEY] ?: defaults.splashSkipEnabled,
                    otpAutofillEnabled = prefs[OTP_AUTOFILL_ENABLED_KEY] ?: defaults.otpAutofillEnabled,
                )
            }

        override suspend fun updateSplashSkipEnabled(enabled: Boolean) {
            dataStore.edit { prefs -> prefs[SPLASH_SKIP_ENABLED_KEY] = enabled }
            Log.i(TAG, "Splash-ad auto-skip enabled=$enabled")
        }

        override suspend fun updateOtpAutofillEnabled(enabled: Boolean) {
            dataStore.edit { prefs -> prefs[OTP_AUTOFILL_ENABLED_KEY] = enabled }
            Log.i(TAG, "Verification-code autofill enabled=$enabled")
        }

        override val assistantName: Flow<String> =
            dataStore.data.map { prefs ->
                prefs[ASSISTANT_NAME_KEY] ?: AssistSettingsRepository.DEFAULT_ASSISTANT_NAME
            }

        override suspend fun updateAssistantName(name: String): Result<String> {
            val trimmed = name.trim()
            val valid = trimmed.isNotEmpty() && trimmed.length <= AssistSettingsRepository.MAX_ASSISTANT_NAME_LENGTH
            if (!valid) {
                return Result.failure(
                    IllegalArgumentException(
                        "Name must be 1-${AssistSettingsRepository.MAX_ASSISTANT_NAME_LENGTH} characters",
                    ),
                )
            }
            dataStore.edit { prefs -> prefs[ASSISTANT_NAME_KEY] = trimmed }
            Log.i(TAG, "Assistant renamed")
            return Result.success(trimmed)
        }

        override val voiceLanguage: Flow<VoiceLanguage> =
            dataStore.data.map { prefs ->
                VoiceLanguage.entries.firstOrNull { it.code == prefs[VOICE_LANGUAGE_KEY] }
                    ?: VoiceLanguage.forLocale(Locale.getDefault())
            }

        override suspend fun updateVoiceLanguage(language: VoiceLanguage) {
            dataStore.edit { prefs -> prefs[VOICE_LANGUAGE_KEY] = language.code }
            Log.i(TAG, "Voice language=${language.code}")
        }

        override val providers: Flow<List<ModelProvider>> =
            dataStore.data.map { prefs -> decode<List<ModelProvider>>(prefs[MODEL_PROVIDERS_KEY]) ?: emptyList() }

        override suspend fun saveProvider(provider: ModelProvider) {
            dataStore.edit { prefs ->
                val current = decode<List<ModelProvider>>(prefs[MODEL_PROVIDERS_KEY]).orEmpty()
                val replaced = current.any { it.id == provider.id }
                val next =
                    if (replaced) current.map { if (it.id == provider.id) provider else it } else current + provider
                prefs[MODEL_PROVIDERS_KEY] = json.encodeToString(next)
            }
            Log.i(TAG, "Provider saved: ${provider.name} (${provider.kind})")
        }

        override suspend fun deleteProvider(id: String) {
            dataStore.edit { prefs ->
                val current = decode<List<ModelProvider>>(prefs[MODEL_PROVIDERS_KEY]).orEmpty()
                prefs[MODEL_PROVIDERS_KEY] = json.encodeToString(current.filterNot { it.id == id })
            }
            Log.i(TAG, "Provider deleted")
        }

        override val assistantModel: Flow<ModelChoice> =
            dataStore.data.map { prefs -> decode<ModelChoice>(prefs[ASSISTANT_MODEL_KEY]) ?: ModelChoice.ON_DEVICE }

        override suspend fun updateAssistantModel(choice: ModelChoice) {
            dataStore.edit { prefs -> prefs[ASSISTANT_MODEL_KEY] = json.encodeToString(choice) }
            Log.i(TAG, "Assistant model=${choice.model.ifBlank { "on-device" }}")
        }

        override val assistantProfile: Flow<AssistantProfile> =
            dataStore.data.map { prefs -> decode<AssistantProfile>(prefs[ASSISTANT_PROFILE_KEY]) ?: AssistantProfile() }

        override suspend fun updateAssistantProfile(profile: AssistantProfile) {
            dataStore.edit { prefs -> prefs[ASSISTANT_PROFILE_KEY] = json.encodeToString(profile) }
        }

        private inline fun <reified T> decode(raw: String?): T? =
            raw?.takeIf { it.isNotBlank() }?.let { text -> runCatching { json.decodeFromString<T>(text) }.getOrNull() }

        private val json = Json { ignoreUnknownKeys = true }

        private companion object {
            const val TAG = "MCP:AssistSettings"
            val MODEL_PROVIDERS_KEY = stringPreferencesKey("model_providers")
            val ASSISTANT_MODEL_KEY = stringPreferencesKey("assistant_model")
            val ASSISTANT_PROFILE_KEY = stringPreferencesKey("assistant_profile")
            val SPLASH_SKIP_ENABLED_KEY = booleanPreferencesKey("assist_splash_skip_enabled")
            val OTP_AUTOFILL_ENABLED_KEY = booleanPreferencesKey("assist_otp_autofill_enabled")
            val ASSISTANT_NAME_KEY = stringPreferencesKey("assistant_name")
            val VOICE_LANGUAGE_KEY = stringPreferencesKey("voice_language")
        }
    }
