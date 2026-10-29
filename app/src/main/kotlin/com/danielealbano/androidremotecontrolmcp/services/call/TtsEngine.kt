package com.danielealbano.androidremotecontrolmcp.services.call

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device text-to-speech (sherpa-onnx, VITS aishell3: 40 MB, Chinese, 174 voices). The model
 * lives in the app's external files directory so it can be pushed with adb or fetched once from
 * the mirror; it is not in the APK.
 */
@Singleton
class TtsEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private var tts: OfflineTts? = null

        val sampleRate: Int get() = tts?.sampleRate() ?: DEFAULT_RATE

        val numSpeakers: Int get() = tts?.numSpeakers() ?: 0

        /** Where the model is looked for: external files first (adb-pushable), then internal. */
        fun modelDir(): File? =
            listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
                .map { File(it, MODEL_SUBDIR) }
                .firstOrNull { File(it, MODEL_FILE).length() > MIN_MODEL_BYTES }

        fun isReady(): Boolean = tts != null || modelDir() != null

        /** Downloads the model files from the mirror, reporting 0..1 progress; no-op when present. */
        fun download(onProgress: (Float) -> Unit): Result<Unit> =
            runCatching {
                if (modelDir() != null) return@runCatching
                val target = File(context.getExternalFilesDir(null) ?: context.filesDir, MODEL_SUBDIR).apply { mkdirs() }
                MODEL_FILES.forEachIndexed { index, (name, remote) ->
                    val file = File(target, name)
                    if (file.length() == 0L) {
                        fetch("$MIRROR/$remote", file) { part ->
                            onProgress((index + part) / MODEL_FILES.size)
                        }
                    }
                }
                onProgress(1f)
            }

        /** Loads the model into memory (a few hundred milliseconds); safe to call twice. */
        @Synchronized
        fun load() {
            if (tts != null) return
            val dir = modelDir() ?: error("语音包未安装")
            val vits =
                OfflineTtsVitsModelConfig(
                    model = File(dir, MODEL_FILE).path,
                    lexicon = File(dir, "lexicon.txt").path,
                    tokens = File(dir, "tokens.txt").path,
                )
            val fsts = RULE_FSTS.map { File(dir, it) }.filter { it.isFile }.joinToString(",") { it.path }
            val config =
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(vits = vits, numThreads = THREADS, debug = false, provider = "cpu"),
                    ruleFsts = fsts,
                    maxNumSentences = 1,
                )
            tts = OfflineTts(assetManager = null, config = config)
            Log.i(TAG, "tts loaded rate=${tts?.sampleRate()} speakers=${tts?.numSpeakers()}")
        }

        /**
         * Synthesises [text] with voice [speaker], handing audio to [onChunk] as it is produced;
         * returning false from [onChunk] stops early (hang-up). Samples are 16-bit PCM at [sampleRate].
         */
        fun speak(
            text: String,
            speaker: Int,
            onChunk: (ShortArray) -> Boolean,
        ) {
            val engine = tts ?: error("语音引擎未加载")
            engine.generateWithCallback(text, speaker.coerceIn(0, (engine.numSpeakers() - 1).coerceAtLeast(0)), SPEED) { samples ->
                if (onChunk(toPcm(samples))) 1 else 0
            }
        }

        fun release() {
            tts?.release()
            tts = null
        }

        private fun toPcm(samples: FloatArray): ShortArray =
            ShortArray(samples.size) { i -> (samples[i].coerceIn(-1f, 1f) * PCM_SCALE).toInt().toShort() }

        private fun fetch(
            url: String,
            into: File,
            onPart: (Float) -> Unit,
        ) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_MS
                conn.readTimeout = READ_MS
                conn.instanceFollowRedirects = true
                check(conn.responseCode in OK_RANGE) { "下载失败 HTTP ${conn.responseCode}: $url" }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
                val tmp = File(into.path + ".part")
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(BUFFER)
                        var done = 0L
                        var n = input.read(buf)
                        while (n >= 0) {
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onPart(done.toFloat() / total)
                            n = input.read(buf)
                        }
                    }
                }
                check(tmp.renameTo(into)) { "无法写入 ${into.name}" }
            } finally {
                conn.disconnect()
            }
        }

        companion object {
            private const val TAG = "MCP:Tts"
            const val MODEL_SUBDIR = "tts/aishell3"
            private const val MODEL_FILE = "model.onnx"
            private const val MIN_MODEL_BYTES = 1_000_000L
            private const val DEFAULT_RATE = 8_000
            private const val THREADS = 2
            private const val SPEED = 1.0f
            private const val PCM_SCALE = 32767f
            private const val CONNECT_MS = 20_000
            private const val READ_MS = 60_000
            private const val BUFFER = 64 * 1024
            private val OK_RANGE = 200..299
            private val RULE_FSTS = listOf("phone.fst", "date.fst", "number.fst", "new_heteronym.fst")
            private const val MIRROR = "https://hf-mirror.com/csukuangfj/vits-zh-aishell3/resolve/main"
            private val MODEL_FILES =
                listOf(
                    MODEL_FILE to "vits-aishell3.int8.onnx",
                    "tokens.txt" to "tokens.txt",
                    "lexicon.txt" to "lexicon.txt",
                    "phone.fst" to "phone.fst",
                    "date.fst" to "date.fst",
                    "number.fst" to "number.fst",
                    "new_heteronym.fst" to "new_heteronym.fst",
                )
        }
    }
