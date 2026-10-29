package com.danielealbano.androidremotecontrolmcp.services.call

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.chat.Attachment
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatApiResult
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBackend
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBubble
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationRef
import com.danielealbano.androidremotecontrolmcp.services.chat.OutgoingMessages
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpChatLogic
import com.danielealbano.androidremotecontrolmcp.services.speech.LocalSttModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/** Everything the call screen shows. */
data class CallUiState(
    val phase: CallPhase = CallPhase.LISTENING,
    val video: Boolean = false,
    val connected: Boolean = false,
    val ended: Boolean = false,
    val startedAt: Long = 0L,
    /** What the person is saying (live) or last said. */
    val userLine: String = "",
    /** The sentence being read out. */
    val assistantLine: String = "",
    val muted: Boolean = false,
    val speakerphone: Boolean = false,
    val echoCanceller: Boolean = false,
    /** 0..1 while the voice pack downloads, null otherwise. */
    val downloading: Float? = null,
    val error: String? = null,
)

/** Supplies a camera frame for a video call turn; null when none could be taken. */
fun interface FrameSource {
    suspend fun capture(): File?
}

/**
 * One call: microphone → recogniser → turn controller → model (streamed) → sentence splitter →
 * speaker, with the echo filter between recogniser and controller. Every turn is written to the
 * contact's chat history so the conversation reads back like any other.
 */
class CallSession(
    private val context: Context,
    private val backend: ChatBackend,
    private val tts: TtsEngine,
    private val settings: AssistSettingsRepository,
    private val localStt: LocalSttModel,
    private val io: CoroutineDispatcher,
) : SttEvents,
    SpeakerEvents {
    private val _ui = MutableStateFlow(CallUiState())
    val ui: StateFlow<CallUiState> = _ui.asStateFlow()

    private val turns = TurnController()
    private val echo = EchoFilter()
    private lateinit var scope: CoroutineScope
    private lateinit var audio: CallAudio
    private lateinit var speaker: CallSpeaker
    private var stt: CallRecognizer? = null
    private var history: List<ChatBubble> = emptyList()
    private lateinit var conversation: ConversationRef

    @Volatile
    private var frames: FrameSource? = null
    private var micJob: Job? = null
    private var tickJob: Job? = null

    @Volatile
    private var running = false

    fun start(
        scope: CoroutineScope,
        video: Boolean,
        frames: FrameSource?,
    ) {
        this.scope = scope
        this.frames = frames
        _ui.update { it.copy(video = video, startedAt = SystemClock.elapsedRealtime()) }
        scope.launch(io) {
            runCatching { connect() }.onFailure { e ->
                release()
                _ui.update { it.copy(error = e.message ?: "无法开始通话") }
            }
        }
    }

    private suspend fun connect() {
        if (!tts.isReady()) {
            _ui.update { it.copy(downloading = 0f) }
            tts.download { p -> _ui.update { it.copy(downloading = p) } }.getOrThrow()
        }
        _ui.update { it.copy(downloading = null) }
        tts.load()
        conversation = backend.store.here()
        history = backend.store.loadAt(conversation)
        val card = backend.store.activeCharacter()
        val voice = card?.let { abs(it.id.hashCode()) % tts.numSpeakers.coerceAtLeast(1) } ?: 0
        // The rooted setup's recogniser when it is running; otherwise the one inside the app.
        // Picked before the microphone is opened: with no recogniser there is nothing to release.
        val recognizer = CallRecognizers.pick(this, localStt)
        audio = CallAudio(context, tts.sampleRate).also { it.open() }
        speaker = CallSpeaker(audio, tts, voice, this).also { it.start(scope) }
        stt = recognizer.also { it.start(settings.voiceLanguage.first().code) }
        running = true
        _ui.update { it.copy(connected = true, echoCanceller = audio.echoCancellerActive) }
        micJob = scope.launch(io) { micLoop() }
        tickJob = scope.launch(io) { tickLoop() }
    }

    /** The camera binds after the call screen is on screen, so frames arrive later than [start]. */
    fun attachFrames(source: FrameSource?) {
        frames = source
    }

    private fun micLoop() {
        val buffer = ShortArray(audio.recordBufferShorts)
        val level = MicLevel()
        while (running) {
            val n = audio.read(buffer)
            if (n > 0 && !_ui.value.muted) stt?.feed(buffer, n)
            if (n > 0) level.add(buffer, n, SystemClock.elapsedRealtime())?.let { Log.d(TAG, "mic peak $it") }
        }
    }

    private suspend fun tickLoop() {
        while (running) {
            delay(TICK_MS)
            act(turns.onTick(SystemClock.elapsedRealtime()))
        }
    }

    /** Lets go of the recogniser, the voice and the audio route, whichever of them were opened. */
    private fun release() {
        stt?.stop()
        if (::speaker.isInitialized) speaker.stop()
        micJob?.cancel()
        tickJob?.cancel()
        if (::audio.isInitialized) audio.close()
    }

    fun hangUp() {
        Log.i(TAG, "hang up (running=$running connected=${_ui.value.connected})")
        if (!running && !_ui.value.connected) {
            _ui.update { it.copy(ended = true) }
            return
        }
        running = false
        release()
        _ui.update { it.copy(ended = true, connected = false) }
    }

    fun setMuted(muted: Boolean) = _ui.update { it.copy(muted = muted) }

    fun setSpeakerphone(on: Boolean) {
        if (::audio.isInitialized) audio.setSpeakerphone(on)
        _ui.update { it.copy(speakerphone = on) }
    }

    // ---- recogniser ----

    override fun onPartial(text: String) {
        // While the phone talks, partials are mostly its own voice: keep the last real line up.
        if (turns.phase == CallPhase.LISTENING) _ui.update { it.copy(userLine = text) }
    }

    override fun onFinal(segment: HeardSegment) {
        if (echo.isEcho(segment, speaker.spoken())) {
            Log.d(TAG, "echo dropped: ${segment.text}")
            return
        }
        _ui.update { it.copy(userLine = segment.text) }
        act(turns.onUserFinal(segment.text, SystemClock.elapsedRealtime()))
    }

    override fun onError(message: String) = _ui.update { it.copy(error = message) }

    // ---- speaker ----

    override fun onSentence(text: String) {
        turns.onAssistantSpeaking()
        _ui.update { it.copy(phase = CallPhase.SPEAKING, assistantLine = text) }
    }

    override fun onReplyPlayed() = act(turns.onAssistantFinished())

    private fun act(action: TurnAction) {
        _ui.update { it.copy(phase = turns.phase) }
        if (action is TurnAction.Send) scope.launch(io) { sendTurn(action.text) }
    }

    private suspend fun sendTurn(text: String) {
        turns.onAssistantStarted()
        _ui.update { it.copy(phase = CallPhase.THINKING, assistantLine = "") }
        val choice = backend.store.modelChoice()
        val frame = if (_ui.value.video && !choice.onDevice) frames?.capture() else null
        val user =
            PhoneMcpChatLogic.newBubble(role = "user", content = text).copy(
                attachment = frame?.let { Attachment(Attachment.IMAGE, "通话画面.jpg", it.path, "image/jpeg") },
            )
        history = history + user
        backend.store.saveAt(conversation, history)
        val messages = OutgoingMessages.toModelMessages(backend.store.systemPrompt(), history)
        val splitter = SentenceSplitter()
        val result =
            runCatching { backend.gateway.chatStream(messages, choice) { delta -> splitter.feed(delta).forEach(speaker::enqueue) } }
                .getOrElse { ChatApiResult(ok = false, error = it.message.orEmpty()) }
        splitter.finish().forEach(speaker::enqueue)
        if (result.ok) {
            backend.presence.markAlive(choice)
            val reply = backend.store.receive(result.content)
            history = history + PhoneMcpChatLogic.newBubble(role = "assistant", content = reply)
            backend.store.saveAt(conversation, history)
        } else {
            backend.presence.markFailed(choice)
            _ui.update { it.copy(error = PhoneMcpChatLogic.formatChatError(result.error.ifBlank { "empty" })) }
        }
        speaker.endOfReply()
    }

    private companion object {
        const val TAG = "MCP:Call"
        const val TICK_MS = 100L
    }
}
