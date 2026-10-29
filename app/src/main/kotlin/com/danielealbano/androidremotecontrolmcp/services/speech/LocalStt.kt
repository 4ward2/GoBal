package com.danielealbano.androidremotecontrolmcp.services.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * The speech recogniser that lives inside the app: sherpa-onnx (already bundled for the call
 * voices) with a small streaming Chinese model, about 25 MB, downloaded once. It needs no root,
 * no network after the download and no recognition service from the phone's maker, which most
 * phones sold in China do not have.
 */
@Singleton
class LocalSttModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private var recognizer: OnlineRecognizer? = null

        /** Where the model is looked for: external files first (adb-pushable), then internal. */
        fun modelDir(): File? =
            listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
                .map { File(it, MODEL_SUBDIR) }
                .firstOrNull { dir -> MODEL_FILES.all { (name, _) -> File(dir, name).length() > 0 } }

        fun installed(): Boolean = recognizer != null || modelDir() != null

        /** Downloads the model files from the mirror, reporting 0..1 progress; no-op when present. */
        fun download(onProgress: (Float) -> Unit): Result<Unit> =
            runCatching {
                if (modelDir() != null) return@runCatching
                val root = context.getExternalFilesDir(null) ?: context.filesDir
                val target = File(root, MODEL_SUBDIR).apply { mkdirs() }
                val count = MODEL_FILES.size
                MODEL_FILES.forEachIndexed { index, (name, remote) ->
                    val file = File(target, name)
                    if (file.length() == 0L) {
                        ModelDownloads.fetch("$MIRROR/$remote", file) { part -> onProgress((index + part) / count) }
                    }
                }
                onProgress(1f)
            }

        /** The loaded recogniser (loading takes well under a second); shared by every listener. */
        @Synchronized
        fun recognizer(): OnlineRecognizer {
            recognizer?.let { return it }
            val dir = modelDir() ?: error(NOT_INSTALLED)
            val transducer =
                OnlineTransducerModelConfig(
                    encoder = File(dir, ENCODER).path,
                    decoder = File(dir, DECODER).path,
                    joiner = File(dir, JOINER).path,
                )
            val tokens = File(dir, TOKENS).path
            val model = OnlineModelConfig(transducer = transducer, tokens = tokens, numThreads = THREADS)
            val endpoints =
                EndpointConfig(
                    rule1 = EndpointRule(false, SILENCE_NO_SPEECH_S, 0f),
                    rule2 = EndpointRule(true, SILENCE_AFTER_SPEECH_S, 0f),
                    rule3 = EndpointRule(false, 0f, MAX_UTTERANCE_S),
                )
            val config =
                OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                    modelConfig = model,
                    endpointConfig = endpoints,
                    enableEndpoint = true,
                )
            val loaded = OnlineRecognizer(assetManager = null, config = config)
            recognizer = loaded
            Log.i(TAG, "in-app recogniser loaded from ${dir.path}")
            return loaded
        }

        companion object {
            const val SAMPLE_RATE = 16_000
            const val SIZE_MB = 25
            const val NOT_INSTALLED = "语音识别还没装好：到 设置 → 对话与语音 下载「手机自带语音识别」（约 25MB，只需一次）"
            private const val TAG = "MCP:LocalStt"
            private const val MODEL_SUBDIR = "stt/zipformer-zh-14m"
            private const val ENCODER = "encoder.onnx"
            private const val DECODER = "decoder.onnx"
            private const val JOINER = "joiner.onnx"
            private const val TOKENS = "tokens.txt"
            private const val THREADS = 2
            private const val FEATURE_DIM = 80
            private const val SILENCE_NO_SPEECH_S = 2.4f
            private const val SILENCE_AFTER_SPEECH_S = 1.2f
            private const val MAX_UTTERANCE_S = 20f
            private const val MIRROR =
                "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/resolve/main"
            private val MODEL_FILES =
                listOf(
                    ENCODER to "encoder-epoch-99-avg-1.int8.onnx",
                    DECODER to "decoder-epoch-99-avg-1.int8.onnx",
                    JOINER to "joiner-epoch-99-avg-1.int8.onnx",
                    TOKENS to "tokens.txt",
                )
        }
    }

/** One act of listening: when it ends, how long it may last, and where the loudness goes. */
class ListenRequest(
    /** Hold-to-talk: listening goes on while this is true. Ignored when [endOnSilence]. */
    val shouldContinue: () -> Boolean,
    val isCancelled: () -> Boolean,
    /** Stop by itself once the speaker has gone quiet (wake word style) instead of on release. */
    val endOnSilence: Boolean,
    val maxMs: Long,
    val onLevel: (Int) -> Unit,
)

/**
 * Listens through the microphone and returns what was said, using [LocalSttModel]. Blocking, like
 * the recogniser of rooted setups it stands in for, and with the same messages when nothing was
 * heard.
 */
@Singleton
class LocalSpeechClient
    @Inject
    constructor(
        private val model: LocalSttModel,
    ) {
        fun available(): Boolean = model.installed()

        fun listen(request: ListenRequest): String {
            val recognizer = model.recognizer()
            val minBuffer =
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = max(minBuffer, SAMPLE_RATE * 2 / BUFFERS_PER_SECOND)
            val recorder = openRecorder(bufferSize)
            var decoder: Decoder? = null
            try {
                val decoding = Decoder(recognizer, recognizer.createStream("")).also { decoder = it }
                recorder.startRecording()
                val heard = capture(recorder, bufferSize, decoding, request)
                if (request.isCancelled()) error(CANCELLED_MESSAGE)
                return heard.ifEmpty { error("未检测到说话，请靠近麦再说") }
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                decoder?.release()
            }
        }

        /** Feeds the microphone to [decoder] until the request says stop; returns the text heard. */
        private fun capture(
            recorder: AudioRecord,
            bufferSize: Int,
            decoder: Decoder,
            request: ListenRequest,
        ): String {
            val pcm = ShortArray(bufferSize / 2)
            val silence = SilenceTracker()
            val started = SystemClock.elapsedRealtime()
            var over = false
            while (!over) {
                val now = SystemClock.elapsedRealtime()
                val n = recorder.read(pcm, 0, pcm.size)
                // A microphone taken by another app answers at once with an error: do not spin on it.
                if (n < 0) error("麦克风用不了：可能被别的应用占用了")
                val level = Pcm.level(pcm, n)
                if (n > 0) {
                    request.onLevel(level)
                    decoder.accept(pcm, n)
                }
                val quiet = n > 0 && silence.finished(level, now)
                val released = !request.endOnSilence && !request.shouldContinue()
                val ended = request.endOnSilence && (quiet || decoder.atEndpoint())
                over = request.isCancelled() || released || ended || now - started >= request.maxMs
            }
            return decoder.finish()
        }

        private fun openRecorder(bufferSize: Int): AudioRecord {
            val sources = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
            val opened =
                sources.firstNotNullOfOrNull { source ->
                    val recorder =
                        AudioRecord(
                            source,
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize,
                        )
                    recorder.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: run {
                        recorder.release()
                        null
                    }
                }
            return opened ?: error("无法打开麦克风")
        }

        companion object {
            /** The same marker the other recogniser throws, so callers treat a cancel alike. */
            const val CANCELLED_MESSAGE = "__hold_cancelled__"
            private const val SAMPLE_RATE = LocalSttModel.SAMPLE_RATE
            private const val BUFFERS_PER_SECOND = 5
        }
    }

/** One utterance going through the recogniser. */
internal class Decoder(
    private val recognizer: OnlineRecognizer,
    private val stream: OnlineStream,
) {
    fun accept(
        pcm: ShortArray,
        count: Int,
    ) {
        stream.acceptWaveform(Pcm.toFloats(pcm, count), LocalSttModel.SAMPLE_RATE)
        drain()
    }

    /** What has been recognised so far. */
    fun text(): String = recognizer.getResult(stream).text.trim()

    /** True when the recogniser itself judges the sentence finished and has words for it. */
    fun atEndpoint(): Boolean = recognizer.isEndpoint(stream) && text().isNotEmpty()

    /** Starts over for the next sentence on the same stream (a call goes on for many). */
    fun reset() = recognizer.reset(stream)

    fun release() = stream.release()

    /** Tells the recogniser no more audio is coming and returns the final text. */
    fun finish(): String {
        // A little trailing silence lets the last syllable through the model's look-ahead.
        stream.acceptWaveform(FloatArray(LocalSttModel.SAMPLE_RATE / 2), LocalSttModel.SAMPLE_RATE)
        stream.inputFinished()
        drain()
        return text()
    }

    private fun drain() {
        while (recognizer.isReady(stream)) recognizer.decode(stream)
    }
}
