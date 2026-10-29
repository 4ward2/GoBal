package com.danielealbano.androidremotecontrolmcp.services.speech

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.math.abs

/** Whether something on this phone is listening on a port (the recogniser of rooted setups). */
object LocalPorts {
    private const val TIMEOUT_MS = 300

    fun isOpen(port: Int): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), TIMEOUT_MS) }
            true
        }.getOrDefault(false)

    private const val LOOPBACK = "127.0.0.1"
}

/**
 * Whether to spend time bringing up the Vosk server of rooted setups before listening. Pure.
 *
 * @param voskUp the server already listens.
 * @param helperGone an earlier attempt to start it on this phone came to nothing.
 * @param inAppReady the recogniser inside the app is installed.
 * @param chinese the language to recognise is one the in-app recogniser knows.
 */
data class SttRoute(
    val voskUp: Boolean,
    val helperGone: Boolean,
    val inAppReady: Boolean,
    val chinese: Boolean,
) {
    fun shouldStartHelper(): Boolean = !voskUp && !helperGone && !(inAppReady && chinese)
}

/** 16-bit microphone samples as the recogniser and the level meter want them. Pure. */
object Pcm {
    private const val FULL_SCALE = 32768f

    /** The first [count] samples as floats in -1..1. */
    fun toFloats(
        pcm: ShortArray,
        count: Int,
    ): FloatArray = FloatArray(count) { pcm[it] / FULL_SCALE }

    /** The mean absolute amplitude (0..32767) of the first [count] samples: a loudness reading. */
    fun level(
        pcm: ShortArray,
        count: Int,
    ): Int = if (count <= 0) 0 else (0 until count).sumOf { abs(pcm[it].toInt()) } / count
}

/**
 * Decides when someone has finished speaking from loudness alone: speech has been heard, and it
 * has then stayed quiet for [silenceMs]. Pure; the clock is passed in.
 */
class SilenceTracker(
    private val speechLevel: Int = SPEECH_LEVEL,
    private val silenceMs: Long = SILENCE_MS,
) {
    /** True once anything loud enough to be speech has been heard. */
    var heard = false
        private set
    private var quietSince = NOT_QUIET

    /** Takes one buffer's [level] at [nowMs]; true when the utterance is over. */
    fun finished(
        level: Int,
        nowMs: Long,
    ): Boolean {
        val speaking = level >= speechLevel
        if (speaking) {
            heard = true
            quietSince = NOT_QUIET
        } else if (heard && quietSince == NOT_QUIET) {
            quietSince = nowMs
        }
        return heard && !speaking && nowMs - quietSince >= silenceMs
    }

    companion object {
        /** The same thresholds as the recogniser of rooted setups, so both feel alike. */
        const val SPEECH_LEVEL = 400
        const val SILENCE_MS = 1_200L
        private const val NOT_QUIET = -1L
    }
}

/** Fetching a model file with progress; the part-file is only renamed once it is complete. */
object ModelDownloads {
    private fun copy(
        input: InputStream,
        into: File,
        total: Long,
        onPart: (Float) -> Unit,
    ) {
        into.outputStream().use { out ->
            val buffer = ByteArray(BUFFER)
            var done = 0L
            var n = input.read(buffer)
            while (n >= 0) {
                out.write(buffer, 0, n)
                done += n
                if (total > 0) onPart(done.toFloat() / total)
                n = input.read(buffer)
            }
        }
    }

    private const val CONNECT_MS = 20_000
    private const val READ_MS = 60_000
    private const val BUFFER = 64 * 1024
    private const val HTTP_OK = 200
    private const val HTTP_OK_LAST = 299

    fun fetch(
        url: String,
        into: File,
        onPart: (Float) -> Unit,
    ) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_MS
            conn.readTimeout = READ_MS
            conn.instanceFollowRedirects = true
            check(conn.responseCode in HTTP_OK..HTTP_OK_LAST) { "下载失败 HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong.coerceAtLeast(0)
            val part = File(into.path + ".part")
            conn.inputStream.use { input -> copy(input, part, total, onPart) }
            check(part.renameTo(into)) { "无法写入 ${into.name}" }
        } finally {
            conn.disconnect()
        }
    }
}
