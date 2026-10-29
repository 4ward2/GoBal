package com.danielealbano.androidremotecontrolmcp.services.call

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBackend
import com.danielealbano.androidremotecontrolmcp.services.speech.LocalSttModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/** Builds one [CallSession] per call, so the view model never touches the audio stack directly. */
class CallSessionFactory
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val backend: ChatBackend,
        private val tts: TtsEngine,
        private val settings: AssistSettingsRepository,
        private val localStt: LocalSttModel,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) {
        fun create(): CallSession = CallSession(context, backend, tts, settings, localStt, io)
    }
