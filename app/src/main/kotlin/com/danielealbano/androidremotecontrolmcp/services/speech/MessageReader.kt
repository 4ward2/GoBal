package com.danielealbano.androidremotecontrolmcp.services.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.call.SentenceSplitter
import com.danielealbano.androidremotecontrolmcp.services.call.TtsEngine
import com.danielealbano.androidremotecontrolmcp.services.chat.MarkdownText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What reading a message aloud is doing right now. */
data class ReadingState(
    /** The id of the message being read, or prepared for reading; null when silent. */
    val messageId: String? = null,
    /** 0..1 while the voice is being downloaded for the first reading, null otherwise. */
    val downloading: Float? = null,
    val error: String? = null,
)

/** Splits a message into what is spoken one piece at a time: Markdown marks and status tags are not read. Pure. */
object ReadingText {
    private const val LIST_MARKS = "•-│ "

    fun sentences(text: String): List<String> {
        val splitter = SentenceSplitter()
        val pieces = splitter.feed(MarkdownText.plain(text)) + splitter.finish()
        return pieces.map { it.trimStart { mark -> mark in LIST_MARKS } }.filter { it.isNotEmpty() }
    }
}

/**
 * Reads one chat message aloud with the same on-device voice the calls use (downloaded once,
 * no network afterwards). Reading another message, or the same one again, stops the current one.
 */
@Singleton
class MessageReader
    @Inject
    constructor(
        private val tts: TtsEngine,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        private val _state = MutableStateFlow(ReadingState())
        val state: StateFlow<ReadingState> = _state.asStateFlow()
        private var job: Job? = null

        /** Starts reading [text] as message [messageId]; when that message is already being read, stops it. */
        @Synchronized
        fun toggle(
            messageId: String,
            text: String,
            voice: Int,
        ) {
            val wasReading = _state.value.messageId == messageId
            stop()
            if (wasReading) return
            _state.value = ReadingState(messageId = messageId)
            job =
                scope.launch {
                    val outcome = runCatching { read(text, voice) }
                    val failure = outcome.exceptionOrNull()?.takeIf { isActive }?.let { it.message ?: "朗读失败" }
                    _state.update { if (it.messageId == messageId) ReadingState(error = failure) else it }
                }
        }

        @Synchronized
        fun stop() {
            job?.cancel()
            job = null
            _state.value = ReadingState()
        }

        fun errorShown() = _state.update { it.copy(error = null) }

        private fun CoroutineScope.read(
            text: String,
            voice: Int,
        ) {
            if (!tts.isReady()) {
                tts.download { part -> _state.update { it.copy(downloading = part) } }.getOrThrow()
                _state.update { it.copy(downloading = null) }
            }
            tts.load()
            val track = openTrack(tts.sampleRate)
            try {
                track.play()
                val speaker = voice.coerceIn(0, (tts.numSpeakers - 1).coerceAtLeast(0))
                ReadingText.sentences(text).forEach { sentence ->
                    if (isActive) tts.speak(sentence, speaker) { pcm -> isActive && track.write(pcm, 0, pcm.size) >= 0 }
                }
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }

        private fun openTrack(sampleRate: Int): AudioTrack {
            val format =
                AudioFormat
                    .Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            val attributes =
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, encoding)
            return AudioTrack
                .Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minimum * BUFFER_FACTOR)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }

        private companion object {
            const val BUFFER_FACTOR = 4
        }
    }
