package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceLanguage
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.danielealbano.androidremotecontrolmcp.data.model.AssistConfig
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AssistSettingsRepositoryImpl")
class AssistSettingsRepositoryImplTest {
    /** In-memory DataStore: avoids the on-disk temp-file rename that is unreliable on Windows. */
    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private lateinit var repository: AssistSettingsRepositoryImpl

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        repository = AssistSettingsRepositoryImpl(InMemoryPreferencesDataStore())
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `both features default to enabled`() =
        runTest {
            assertEquals(AssistConfig(true, true), repository.assistConfig.first())
        }

    @Test
    fun `toggles persist independently`() =
        runTest {
            repository.updateSplashSkipEnabled(false)
            assertEquals(AssistConfig(false, true), repository.assistConfig.first())

            repository.updateOtpAutofillEnabled(false)
            repository.updateSplashSkipEnabled(true)
            assertEquals(AssistConfig(true, false), repository.assistConfig.first())
        }

    @Test
    fun `voice language persists across reads`() =
        runTest {
            repository.updateVoiceLanguage(VoiceLanguage.HI)
            assertEquals(VoiceLanguage.HI, repository.voiceLanguage.first())
            repository.updateVoiceLanguage(VoiceLanguage.EN_IN)
            assertEquals(VoiceLanguage.EN_IN, repository.voiceLanguage.first())
        }

    @Test
    fun `assistant name defaults and can be renamed with trimming`() =
        runTest {
            assertEquals(AssistSettingsRepository.DEFAULT_ASSISTANT_NAME, repository.assistantName.first())
            assertEquals("阿福", repository.updateAssistantName("  阿福 ").getOrThrow())
            assertEquals("阿福", repository.assistantName.first())
        }

    @Test
    fun `invalid assistant names are rejected and not stored`() =
        runTest {
            assertTrue(repository.updateAssistantName("   ").isFailure)
            val tooLong = "名".repeat(AssistSettingsRepository.MAX_ASSISTANT_NAME_LENGTH + 1)
            assertTrue(repository.updateAssistantName(tooLong).isFailure)
            assertEquals(AssistSettingsRepository.DEFAULT_ASSISTANT_NAME, repository.assistantName.first())
        }
}
