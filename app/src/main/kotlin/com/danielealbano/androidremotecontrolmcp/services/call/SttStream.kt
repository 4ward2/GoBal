package com.danielealbano.androidremotecontrolmcp.services.call

import android.os.SystemClock
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatHttpServer
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpChatLogic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/** What the recogniser says while a call runs. */
interface SttEvents {
    fun onPartial(text: String)

    fun onFinal(segment: HeardSegment)

    fun onError(message: String)
}

/**
 * One long recogniser session for the whole call: the phone's Vosk server in continuous mode
 * keeps the socket open across utterances and reports word timings, which become the call-clock
 * span of each [HeardSegment] so the echo filter can line them up with what was being played.
 */
class SttStream(
    private val events: SttEvents,
) : CallRecognizer {
    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_S, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    private val json = Json { ignoreUnknownKeys = true }
    private var socket: WebSocket? = null
    private var streamStartMs = 0L

    override fun start(lang: String?) {
        val base = "ws://127.0.0.1:${ChatHttpServer.STT_PORT}/client/ws/speech"
        val url = PhoneMcpChatLogic.buildVoskWsUrl(base, lang) + "&continuous=1&words=1"
        streamStartMs = SystemClock.elapsedRealtime()
        socket = http.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    /** Sends one microphone buffer (16 kHz mono PCM) to the recogniser. */
    override fun feed(
        pcm: ShortArray,
        count: Int,
    ) {
        val s = socket ?: return
        val bytes = ByteArray(count * 2)
        var o = 0
        for (i in 0 until count) {
            val v = pcm[i].toInt()
            bytes[o++] = (v and BYTE_MASK).toByte()
            bytes[o++] = ((v shr BITS_PER_BYTE) and BYTE_MASK).toByte()
        }
        s.send(bytes.toByteString())
    }

    override fun stop() {
        socket?.send("EOS")
        socket?.close(NORMAL_CLOSE, "hangup")
        socket = null
    }

    private val listener =
        object : WebSocketListener() {
            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                PhoneMcpChatLogic.parseVoskError(text)?.let {
                    events.onError(it)
                    return
                }
                val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val result = root["result"] as? JsonObject ?: return
                val hyp = (result["hypotheses"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return
                val transcript =
                    hyp["transcript"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        .orEmpty()
                val isFinal = result["final"]?.jsonPrimitive?.contentOrNull == "true"
                if (!isFinal) {
                    if (transcript.isNotEmpty()) events.onPartial(transcript)
                    return
                }
                if (transcript.isEmpty()) return
                events.onFinal(segmentOf(transcript, hyp["words"] as? JsonArray))
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: okhttp3.Response?,
            ) {
                events.onError("识别连接断开：${t.message ?: "error"}")
            }
        }

    /** Word timings are seconds from the stream's start; without them the segment is stamped "now". */
    private fun segmentOf(
        transcript: String,
        words: JsonArray?,
    ): HeardSegment {
        val times = words.orEmpty().mapNotNull { w -> (w as? JsonObject)?.let { timesOf(it) } }
        val now = SystemClock.elapsedRealtime()
        if (times.isEmpty()) return HeardSegment(transcript, now - FALLBACK_SPAN_MS, now)
        val start = streamStartMs + (times.minOf { it.first } * MS_PER_S).toLong()
        val end = streamStartMs + (times.maxOf { it.second } * MS_PER_S).toLong()
        return HeardSegment(transcript, start, end)
    }

    private fun timesOf(word: JsonObject): Pair<Double, Double>? {
        val s = word["s"]?.jsonPrimitive?.doubleOrNull ?: return null
        val e = word["e"]?.jsonPrimitive?.doubleOrNull ?: return null
        return s to e
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    private companion object {
        const val CONNECT_S = 5L
        const val NORMAL_CLOSE = 1000
        const val BYTE_MASK = 0xff
        const val BITS_PER_BYTE = 8
        const val MS_PER_S = 1000.0
        const val FALLBACK_SPAN_MS = 1500L
    }
}
