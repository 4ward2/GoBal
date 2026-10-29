package com.danielealbano.androidremotecontrolmcp.services.chat

import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * On-device HTTP on 127.0.0.1 for phones with the root helper: PhoneMCP wake/chat/status, and the STT probe.
 */
class ChatHttpServer(
    port: Int = PORT,
) : NanoHTTPD(HOST, port) {
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.substringBefore('?')
        return try {
            when {
                uri == "/health" || uri == "/phonemcp/health" || uri == "/phonemcp/v1/health" -> {
                    json(Response.Status.OK, """{"ok":true,"service":"arc-mcp-chat"}""")
                }

                uri == "/phonemcp/v1/wake" || uri == "/phonemcp/wake" -> {
                    handleWake(session)
                }

                uri == "/phonemcp/v1/chat" || uri == "/phonemcp/chat" -> {
                    handleChat(session)
                }

                uri == "/phonemcp/v1/status" || uri == "/phonemcp/status" -> {
                    handleStatus()
                }

                uri == "/stt/health" -> {
                    handleSttHealth()
                }

                uri == "/stt/ensure" -> {
                    handleSttEnsure(session)
                }

                else -> {
                    json(Response.Status.NOT_FOUND, errorJson("not found"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve failed $uri", e)
            json(Response.Status.INTERNAL_ERROR, errorJson(e.message ?: "error"))
        }
    }

    private fun handleWake(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return json(Response.Status.METHOD_NOT_ALLOWED, errorJson("POST required"))
        }
        val body = readBody(session)
        val obj =
            runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrElse {
                return json(Response.Status.BAD_REQUEST, errorJson("invalid json"))
            }
        val text = (obj.optString("text").ifBlank { obj.optString("command") }).trim()
        val source = obj.optString("source", "arc-mcp-chat").ifBlank { "arc-mcp-chat" }
        if (text.isEmpty()) {
            return json(Response.Status.BAD_REQUEST, errorJson("text required"))
        }

        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val script =
            """
            TMP=/data/local/tmp/xiaoai-wake-text.txt
            echo '$b64' | base64 -d > "${'$'}TMP" || exit 1
            TEXT=$(cat "${'$'}TMP")
            sh /data/adb/phonemcp-voice/local_wake.sh "${'$'}TEXT" ${shellSingleQuote(source)}
            """.trimIndent().replace("\n", "; ")

        // local_wake should return in a few seconds; keep headroom but fail clearly.
        val (code, out, err) = su(script, timeoutMs = 35_000)
        val combined = (out + "\n" + err).trim()
        val wakeId = Regex("""wake_id=([0-9a-fA-F-]{8,})""").find(combined)?.groupValues?.get(1)
        if (code == 124 || err.trim() == "su timeout") {
            return json(
                Response.Status.INTERNAL_ERROR,
                errorJson("wake su timeout: Magisk su 或 local_wake.sh 卡住（常见于 am force-stop）"),
            )
        }
        if (code != 0 || wakeId.isNullOrBlank() || !combined.contains("LAUNCHED")) {
            return json(
                Response.Status.INTERNAL_ERROR,
                errorJson("wake failed: ${combined.take(400).ifBlank { "exit $code" }}"),
            )
        }
        val payload =
            JSONObject()
                .put("ok", true)
                .put("wake_id", wakeId)
                .put("text", text)
                .put("ts", System.currentTimeMillis() / 1000)
                .put("message", "wake written; agent_runner launched")
        return json(Response.Status.OK, payload.toString())
    }

    /**
     * One-shot assistant reply via Magisk [VOICE_DIR]/provider.json (same LLM as agent_runner).
     * Used by a contact with no provider chosen, when the helper's credentials are configured.
     */
    private fun handleChat(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return json(Response.Status.METHOD_NOT_ALLOWED, errorJson("POST required"))
        }
        val body = readBody(session)
        if (body.isBlank()) {
            return json(Response.Status.BAD_REQUEST, errorJson("json body required"))
        }
        val timeoutS =
            runCatching { JSONObject(body).optInt("timeout_s", 45) }
                .getOrElse { 45 }
                .coerceIn(5, 120)
        val b64 = Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // Android su has no host python3 — reuse NetHunter chroot via chat-once.sh
        // (same stack as agent-watch.sh / local_wake.sh).
        val script =
            """
            TMP=/data/local/tmp/xiaoai-chat-req.json
            echo '$b64' | base64 -d > "${'$'}TMP" || exit 1
            sh $VOICE_DIR/chat-once.sh "${'$'}TMP"
            """.trimIndent().replace("\n", "; ")
        val (code, out, err) = su(script, timeoutMs = timeoutS * 1000L)
        val raw = out.trim()
        if (code == 124 || err.trim() == "su timeout") {
            return json(Response.Status.INTERNAL_ERROR, errorJson("su timeout"))
        }
        if (raw.startsWith("{")) {
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (obj != null) {
                if (obj.optBoolean("ok", false) && obj.optString("content").isNotBlank()) {
                    return json(Response.Status.OK, obj.toString())
                }
                val detail = obj.optString("error").ifBlank { raw.take(400) }
                return json(Response.Status.INTERNAL_ERROR, errorJson(detail))
            }
        }
        val detail =
            (raw + "\n" + err).trim().take(400).ifBlank { "chat failed exit=$code" }
        return json(Response.Status.INTERNAL_ERROR, errorJson(detail))
    }

    private fun handleStatus(): Response {
        val lastRun = readRemoteJson("$VOICE_DIR/last-run.json")
        val wake = readRemoteJson("$VOICE_DIR/wake-request.json")
        val (_, psOut, _) =
            su(
                "ps -A 2>/dev/null | grep -E 'agent-watch|agent_runner' | grep -v grep || true",
                timeoutMs = 8_000,
            )
        val payload =
            JSONObject()
                .put("ok", true)
                .put("last_run", lastRun ?: JSONObject.NULL)
                .put("wake", wake ?: JSONObject.NULL)
                .put("agent_watch", psOut.contains("agent-watch"))
                .put("agent_runner", psOut.contains("agent_runner"))
                .put("stt_listening", isLocalPortListening(STT_PORT))
        val providerRaw = readRemoteJson("$VOICE_DIR/provider.json")
        val summary = PhoneMcpChatLogic.summarizeProvider(providerRaw?.toString().orEmpty())
        payload.put(
            "provider",
            JSONObject()
                .put("configured", summary.configured)
                .put("kind", summary.kind)
                .put("model", summary.model)
                .put("endpoint", summary.endpoint)
                .put("auth_env", summary.authEnv),
        )
        return json(Response.Status.OK, payload.toString())
    }

    private fun handleSttHealth(): Response {
        val up = isLocalPortListening(STT_PORT)
        val payload =
            JSONObject()
                .put("ok", up)
                .put("listening", up)
                .put("port", STT_PORT)
        return json(if (up) Response.Status.OK else Response.Status.SERVICE_UNAVAILABLE, payload.toString())
    }

    private fun handleSttEnsure(session: IHTTPSession): Response {
        if (session.method != Method.GET && session.method != Method.POST) {
            return json(Response.Status.METHOD_NOT_ALLOWED, errorJson("GET or POST required"))
        }
        // Always drain POST body (client may send "{}"). Leaving unread bytes on a
        // keep-alive socket poisons the next request line as "{}POST" → NanoHTTPD BAD REQUEST.
        if (session.method == Method.POST) {
            runCatching { readBodyBytes(session) }
        }
        if (isLocalPortListening(STT_PORT)) {
            return json(
                Response.Status.OK,
                JSONObject()
                    .put("ok", true)
                    .put("listening", true)
                    .put("restarted", false)
                    .put("port", STT_PORT)
                    .toString(),
            )
        }
        val (code, out, err) =
            su(
                "sh /data/adb/phonemcp-voice/start-stt.sh",
                timeoutMs = 25_000,
            )
        var up = false
        var waits = 0
        while (waits < 40) {
            if (isLocalPortListening(STT_PORT)) {
                up = true
                break
            }
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                break
            }
            waits += 1
        }
        val detail = (out + "\n" + err).trim().take(300)
        if (!up) {
            return json(
                Response.Status.SERVICE_UNAVAILABLE,
                errorJson(
                    "STT :$STT_PORT still down after start-stt (exit=$code) ${detail.ifBlank { "" }}".trim(),
                ),
            )
        }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("listening", true)
                .put("restarted", true)
                .put("port", STT_PORT)
                .put("start_exit", code)
                .toString(),
        )
    }

    private fun readRemoteJson(path: String): JSONObject? {
        val (code, out, _) = su("cat ${shellSingleQuote(path)} 2>/dev/null || true", timeoutMs = 8_000)
        val raw = out.trim()
        if (code != 0 || !raw.startsWith("{")) return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun readBody(session: IHTTPSession): String = String(readBodyBytes(session), Charsets.UTF_8)

    private fun readBodyBytes(session: IHTTPSession): ByteArray {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (_: Exception) {
            }
            val tmp = map["postData"]
            if (!tmp.isNullOrEmpty()) return tmp.toByteArray(Charsets.UTF_8)
            return ByteArray(0)
        }
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = session.inputStream.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return if (off == len) buf else buf.copyOf(off)
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 8765
        const val STT_PORT = 8888
        private const val TAG = "MCP:ChatHttp"
        private const val VOICE_DIR = "/data/adb/phonemcp-voice"

        /**
         * Whether something accepts connections on 127.0.0.1:[port]. A connect probe, because apps
         * may not read /proc/net/tcp since Android 10 (SELinux proc_net_tcp_udp): that check always
         * said "down", so every microphone tap restarted the recognizer and lost ~10 s of speech.
         */
        fun isLocalPortListening(port: Int): Boolean =
            runCatching {
                Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), PROBE_TIMEOUT_MS) }
            }.isSuccess

        private const val LOOPBACK = "127.0.0.1"
        private const val PROBE_TIMEOUT_MS = 300

        fun errorJson(msg: String): String {
            // Hand-build JSON: org.json stubs on the pure JVM unit-test classpath return null from put/toString.
            val escaped =
                msg
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
            return """{"ok":false,"error":"$escaped"}"""
        }

        fun json(
            status: Response.Status,
            body: String,
        ): Response {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val resp =
                newFixedLengthResponse(
                    status,
                    "application/json; charset=utf-8",
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong(),
                )
            // No cross-origin headers: the callers are this app and the phone's own scripts, and a
            // web page in a browser must not be able to read what this server answers.
            return resp
        }

        fun shellSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        fun su(
            script: String,
            timeoutMs: Long,
        ): Triple<Int, String, String> =
            try {
                val pb = ProcessBuilder("su", "-c", script)
                pb.redirectErrorStream(false)
                val p = pb.start()
                val outFuture = proxyPoolRead(p.inputStream)
                val errFuture = proxyPoolRead(p.errorStream)
                val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    p.destroyForcibly()
                    Triple(124, "", "su timeout")
                } else {
                    Triple(p.exitValue(), outFuture(), errFuture())
                }
            } catch (e: Exception) {
                Triple(127, "", e.message ?: "su failed")
            }

        private val ioPool = Executors.newCachedThreadPool()

        private fun proxyPoolRead(stream: InputStream): () -> String {
            val f =
                ioPool.submit<String> {
                    stream.bufferedReader(Charset.forName("UTF-8")).use { it.readText() }
                }
            return {
                try {
                    f.get(5_000, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                    ""
                }
            }
        }
    }
}
