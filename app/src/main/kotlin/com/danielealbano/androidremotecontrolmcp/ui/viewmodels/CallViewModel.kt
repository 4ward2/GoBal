package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.call.CallSession
import com.danielealbano.androidremotecontrolmcp.services.call.CallSessionFactory
import com.danielealbano.androidremotecontrolmcp.services.call.CallUiState
import com.danielealbano.androidremotecontrolmcp.services.call.FrameSource
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBackend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Who is on the other end of the call. */
data class Callee(
    val name: String = "",
    val avatar: Bitmap? = null,
)

/** One call at a time; a new call replaces a finished one. */
@HiltViewModel
class CallViewModel
    @Inject
    constructor(
        private val sessions: CallSessionFactory,
        private val backend: ChatBackend,
        private val settings: AssistSettingsRepository,
        private val characters: CharacterRepository,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) : ViewModel() {
        private var session: CallSession? = null
        private var mirror: Job? = null
        private val _ui = MutableStateFlow(CallUiState())
        val ui: StateFlow<CallUiState> = _ui.asStateFlow()

        /** True from [start] until the call ends; the call screen is shown exactly while this holds. */
        private val _active = MutableStateFlow(false)
        val active: StateFlow<Boolean> = _active.asStateFlow()

        private val _callee = MutableStateFlow(Callee())
        val callee: StateFlow<Callee> = _callee.asStateFlow()

        /** Starts a call with the active contact. Ignored while a call is already running. */
        fun start(video: Boolean) {
            if (_active.value) return
            // A finished call must not leak its final state into this one: stop mirroring it first.
            mirror?.cancel()
            _ui.value = CallUiState(video = video)
            val s = sessions.create()
            session = s
            _active.value = true
            viewModelScope.launch(io) { _callee.value = resolveCallee() }
            mirror =
                viewModelScope.launch {
                    s.ui.collect {
                        _ui.value = it
                        if (it.ended) _active.value = false
                    }
                }
            s.start(viewModelScope, video, null)
        }

        /** The camera of a video call, once it is bound; null when it goes away. */
        fun attachCamera(frames: FrameSource?) {
            session?.attachFrames(frames)
        }

        private suspend fun resolveCallee(): Callee {
            val card = backend.store.activeCharacter()
            val name = card?.name ?: settings.assistantName.first()
            val avatar =
                card
                    ?.takeIf { it.hasAvatar }
                    ?.let { characters.avatarFile(it.id) }
                    ?.let { BitmapFactory.decodeFile(it.path) }
            return Callee(name, avatar)
        }

        fun hangUp() {
            session?.hangUp()
        }

        fun toggleMute() {
            session?.setMuted(!_ui.value.muted)
        }

        fun toggleSpeakerphone() {
            session?.setSpeakerphone(!_ui.value.speakerphone)
        }

        override fun onCleared() {
            session?.hangUp()
        }
    }
