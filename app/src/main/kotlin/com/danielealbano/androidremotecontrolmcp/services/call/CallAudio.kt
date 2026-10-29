package com.danielealbano.androidremotecontrolmcp.services.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlin.math.max

/**
 * The phone's ears and mouth during a call, set up the way a telephony app does it so the
 * platform's acoustic echo canceller has both sides to work with: the microphone opened as
 * `VOICE_COMMUNICATION`, playback tagged `USAGE_VOICE_COMMUNICATION`, and the audio mode switched
 * to in-communication. Without all three the phone hears its own voice and transcribes it.
 */
class CallAudio(
    private val context: Context,
    private val playbackRate: Int,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var framesWritten = 0L
    private var previousMode = AudioManager.MODE_NORMAL

    /** Whether the platform reported an echo canceller on the microphone session. */
    var echoCancellerActive: Boolean = false
        private set

    val recordBufferShorts: Int
        get() = max(AudioRecord.getMinBufferSize(RECORD_RATE, CHANNEL_IN, ENCODING), RECORD_RATE / FRAMES_PER_SECOND) / 2

    fun open() {
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        openRecorder()
        openTrack()
    }

    private fun openRecorder() {
        val bytes = recordBufferShorts * 2
        val rec =
            AudioRecord
                .Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(RECORD_RATE)
                        .setChannelMask(CHANNEL_IN)
                        .build(),
                ).setBufferSizeInBytes(bytes)
                .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "无法打开麦克风" }
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
            echoCancellerActive = aec?.enabled == true
        }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true }
        Log.i(TAG, "mic opened session=${rec.audioSessionId} aec=$echoCancellerActive")
        rec.startRecording()
        record = rec
    }

    private fun openTrack() {
        val minBytes = AudioTrack.getMinBufferSize(playbackRate, AudioFormat.CHANNEL_OUT_MONO, ENCODING)
        val t =
            AudioTrack
                .Builder()
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(playbackRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                ).setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minBytes, playbackRate * 2 * TRACK_BUFFER_SECONDS))
                .build()
        t.play()
        track = t
    }

    /** Reads one buffer of microphone audio; returns the number of samples, 0 when closed. */
    fun read(into: ShortArray): Int = record?.read(into, 0, into.size)?.coerceAtLeast(0) ?: 0

    /**
     * Queues [samples] (mono, [playbackRate]) for playback and returns when, on the call clock,
     * they will start and stop being audible: after whatever is already queued has drained.
     */
    fun write(samples: ShortArray): Pair<Long, Long> {
        val t = track ?: return 0L to 0L
        val queuedFrames = (framesWritten - t.playbackHeadPosition.toLong()).coerceAtLeast(0)
        val startMs = SystemClock.elapsedRealtime() + queuedFrames * MS_PER_S / playbackRate
        val endMs = startMs + samples.size * MS_PER_S / playbackRate
        var offset = 0
        while (offset < samples.size) {
            val n = t.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            offset += n
        }
        framesWritten += offset
        return startMs to endMs
    }

    /** How many milliseconds of queued speech are still to be played. */
    fun queuedMs(): Long {
        val t = track ?: return 0L
        return (framesWritten - t.playbackHeadPosition.toLong()).coerceAtLeast(0) * MS_PER_S / playbackRate
    }

    /** Ear-piece for a phone-to-the-ear call, loudspeaker for hands-free. */
    fun setSpeakerphone(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wanted = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == wanted }
            if (device != null) audioManager.setCommunicationDevice(device) else audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    fun close() {
        runCatching { record?.stop() }
        record?.release()
        record = null
        aec?.release()
        ns?.release()
        runCatching { track?.stop() }
        track?.release()
        track = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        audioManager.mode = previousMode
    }

    companion object {
        const val RECORD_RATE = 16_000
        private const val TAG = "MCP:CallAudio"
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val FRAMES_PER_SECOND = 10
        private const val TRACK_BUFFER_SECONDS = 2
        private const val MS_PER_S = 1000L
    }
}
