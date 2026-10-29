package com.danielealbano.androidremotecontrolmcp.services.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.speech.ListenRequest
import com.danielealbano.androidremotecontrolmcp.services.speech.LocalPorts
import com.danielealbano.androidremotecontrolmcp.services.speech.LocalSpeechClient
import com.danielealbano.androidremotecontrolmcp.services.speech.LocalSttModel
import com.danielealbano.androidremotecontrolmcp.services.speech.SttRoute
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

/**
 * Listening for dictation and the wake: the Vosk server of rooted setups (Magisk, :8888) when it
 * is running, otherwise the recogniser inside the app ([LocalSpeechClient]), which needs no root.
 */
@Singleton
class VoskSpeechClient
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val assistSettings: AssistSettingsRepository,
        private val local: LocalSpeechClient,
    ) {
        private val http =
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()

        // No su binary anywhere: the phone is not rooted and there is no helper to wait for.
        @Volatile
        private var helperGone = !RootHelper.present()

        /**
         * Makes the Vosk server of rooted setups come up when that is worth trying, by running
         * [startHelper] (which blocks for several seconds while it waits). Skipped when Vosk is
         * already up, when the in-app recogniser can take this language, and after one attempt
         * found no helper on this phone: an unrooted phone must start listening at once.
         */
        fun prepare(startHelper: () -> Unit) {
            val port = ChatHttpServer.STT_PORT
            val chinese = languageFor(null)?.startsWith("zh") != false
            val route = SttRoute(LocalPorts.isOpen(port), helperGone, local.available(), chinese)
            if (!route.shouldStartHelper()) return
            runCatching(startHelper)
            helperGone = !LocalPorts.isOpen(port)
        }

        /**
         * @param onLevel 每个录音缓冲的平均绝对振幅（0..32767）。缓冲约 200ms，
         *   所以这是 ~5Hz 的音量包络，供唤醒辉光做声音反应；调用方需自行平滑。
         */
        fun listenOnce(
            isCancelled: () -> Boolean = { false },
            onLevel: (Int) -> Unit = {},
            lang: String? = null,
        ): String =
            runSession(
                Session(
                    shouldContinue = { !isCancelled() },
                    isCancelled = isCancelled,
                    silenceEos = true,
                    maxMs = MAX_MS,
                    onLevel = onLevel,
                    lang = lang,
                ),
            )

        /** Hold-to-talk. EOS when shouldContinue becomes false; cancel via isCancelled. */
        fun listenHeld(
            shouldContinue: () -> Boolean,
            isCancelled: () -> Boolean,
            onLevel: (Int) -> Unit = {},
            lang: String? = null,
        ): String =
            runSession(
                Session(
                    shouldContinue = shouldContinue,
                    isCancelled = isCancelled,
                    silenceEos = false,
                    maxMs = HOLD_MAX_MS,
                    onLevel = onLevel,
                    lang = lang,
                ),
            )

        /** One recording: when it ends, how long it may last, and the recognizer language. */
        private class Session(
            val shouldContinue: () -> Boolean,
            val isCancelled: () -> Boolean,
            val silenceEos: Boolean,
            val maxMs: Long,
            val onLevel: (Int) -> Unit,
            val lang: String? = null,
        )

        /** [Session.lang] when a caller chose one, otherwise the language picked in settings. */
        private fun languageFor(session: Session?): String? =
            session?.lang ?: runCatching { runBlocking { assistSettings.voiceLanguage.first().code } }.getOrNull()

        private fun runSession(session: Session): String {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                error("需要麦克风权限")
            }
            if (!LocalPorts.isOpen(ChatHttpServer.STT_PORT)) return listenInApp(session)
            val minBuf =
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = max(minBuf, SAMPLE_RATE * 2 / 5)
            val recorder = openRecorder(bufSize)
            try {
                return transcribe(recorder, bufSize, session)
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }
        }

        /** No Vosk server on this phone (it is not rooted, or the server is down): recognise in the app. */
        private fun listenInApp(session: Session): String {
            if (!local.available()) error(LocalSttModel.NOT_INSTALLED)
            val request =
                ListenRequest(
                    shouldContinue = session.shouldContinue,
                    isCancelled = session.isCancelled,
                    endOnSilence = session.silenceEos,
                    maxMs = session.maxMs,
                    onLevel = session.onLevel,
                )
            return local.listen(request)
        }

        private fun openRecorder(bufSize: Int): AudioRecord {
            val sources = intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
            for (src in sources) {
                val rec =
                    AudioRecord(
                        src,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize,
                    )
                if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
                rec.release()
            }
            error("无法打开麦克风")
        }

        private fun transcribe(
            recorder: AudioRecord,
            bufSize: Int,
            session: Session,
        ): String {
            val shouldContinue = session.shouldContinue
            val isCancelled = session.isCancelled
            val silenceEos = session.silenceEos
            val maxMs = session.maxMs
            val onLevel = session.onLevel
            val lang = languageFor(session)
            val done = CountDownLatch(1)
            val opened = CountDownLatch(1)
            val settled = AtomicBoolean(false)
            val lastPartial = AtomicReference("")
            val errorMsg = AtomicReference<String?>(null)
            val result = AtomicReference<String?>(null)
            val eosSent = AtomicBoolean(false)

            fun settleSuccess(text: String) {
                if (!settled.compareAndSet(false, true)) return
                result.set(text)
                done.countDown()
            }

            fun settleError(msg: String) {
                if (!settled.compareAndSet(false, true)) return
                errorMsg.set(msg)
                done.countDown()
            }

            val listener =
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        opened.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        PhoneMcpChatLogic.parseVoskError(text)?.let {
                            settleError(it)
                            return
                        }
                        val hyp = PhoneMcpChatLogic.parseVoskMessage(text) ?: return
                        lastPartial.set(hyp.transcript)
                        if (hyp.isFinal) {
                            val out = hyp.transcript.ifBlank { lastPartial.get() }.trim()
                            if (out.isEmpty()) {
                                settleError("听写为空，请靠近麦克风再说一次")
                            } else {
                                settleSuccess(out)
                            }
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: okhttp3.Response?,
                    ) {
                        opened.countDown()
                        settleError("Vosk 连接失败：${t.message ?: "error"}")
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        val partial = lastPartial.get().trim()
                        if (partial.isNotEmpty()) {
                            settleSuccess(partial)
                        } else {
                            settleError("Vosk 已断开且没有识别结果")
                        }
                    }
                }

            val base = "ws://127.0.0.1:${ChatHttpServer.STT_PORT}/client/ws/speech"
            val url = PhoneMcpChatLogic.buildVoskWsUrl(base, lang)
            val req =
                Request
                    .Builder()
                    .url(url)
                    .build()
            val ws = http.newWebSocket(req, listener)
            try {
                if (!opened.await(4, TimeUnit.SECONDS) && result.get() == null && errorMsg.get() == null) {
                    error("Vosk WS 连接超时")
                }
                errorMsg.get()?.let { throw IllegalStateException(it) }
                recorder.startRecording()
                val pcm = ShortArray(bufSize / 2)
                var heard = false
                var peak = 0
                var frames = 0
                var silenceAt = 0L
                val started = System.currentTimeMillis()
                while (!settled.get() && System.currentTimeMillis() - started < maxMs) {
                    if (isCancelled()) {
                        settleError(CANCELLED_MESSAGE)
                        break
                    }
                    if (!silenceEos && !shouldContinue()) {
                        if (!heard && lastPartial.get().isBlank()) {
                            settleError("未检测到说话，请靠近麦再说")
                        } else if (eosSent.compareAndSet(false, true)) {
                            ws.send("EOS")
                            done.await(3, TimeUnit.SECONDS)
                        }
                        break
                    }
                    val n = recorder.read(pcm, 0, pcm.size)
                    if (n <= 0) continue
                    frames += 1
                    var sum = 0
                    for (i in 0 until n) {
                        val a = abs(pcm[i].toInt())
                        sum += a
                        if (a > peak) peak = a
                    }
                    val avg = if (n > 0) sum / n else 0
                    onLevel(avg)
                    if (avg >= SPEECH_ABS) {
                        heard = true
                        silenceAt = 0L
                    } else if (heard && silenceEos) {
                        if (silenceAt == 0L) silenceAt = System.currentTimeMillis()
                        if (System.currentTimeMillis() - silenceAt >= SILENCE_MS && eosSent.compareAndSet(false, true)) {
                            ws.send("EOS")
                        }
                    }
                    val bytes = ByteArray(n * 2)
                    var o = 0
                    for (i in 0 until n) {
                        val v = pcm[i].toInt()
                        bytes[o++] = (v and 0xff).toByte()
                        bytes[o++] = ((v shr 8) and 0xff).toByte()
                    }
                    ws.send(bytes.toByteString())
                }
                if (!settled.get()) {
                    if (heard && eosSent.compareAndSet(false, true)) {
                        ws.send("EOS")
                        done.await(3, TimeUnit.SECONDS)
                    } else {
                        settleError(
                            if (peak < 80) {
                                "未检测到说话：麦克风电平过低"
                            } else if (frames == 0) {
                                "未采到麦克风数据"
                            } else {
                                "未检测到说话，请靠近麦再说"
                            },
                        )
                    }
                } else {
                    done.await(1, TimeUnit.SECONDS)
                }
                if (isCancelled() || errorMsg.get() == CANCELLED_MESSAGE) {
                    error(CANCELLED_MESSAGE)
                }
                errorMsg.get()?.let { throw IllegalStateException(it) }
                return result.get()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: lastPartial.get().trim().takeIf { it.isNotEmpty() }
                    ?: error("听写为空，请靠近麦克风再说一次")
            } finally {
                ws.close(1000, "done")
            }
        }

        companion object {
            const val CANCELLED_MESSAGE = "__hold_cancelled__"
            private const val SAMPLE_RATE = 16_000
            private const val SPEECH_ABS = 400
            private const val SILENCE_MS = 1_200L
            private const val MAX_MS = 12_000L
            private const val HOLD_MAX_MS = 60_000L
        }
    }
