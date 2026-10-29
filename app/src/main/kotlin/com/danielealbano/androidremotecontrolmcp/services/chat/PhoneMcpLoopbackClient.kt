package com.danielealbano.androidremotecontrolmcp.services.chat

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Loopback client for [ChatHttpServer] on 127.0.0.1:8765. */
@Singleton
class PhoneMcpLoopbackClient
    @Inject
    constructor() {
        fun chat(messages: List<Pair<String, String>>, timeoutS: Int = 45): ChatApiResult {
            val body = PhoneMcpChatLogic.chatRequestJson(messages, timeoutS)
            val raw =
                http(
                    method = "POST",
                    path = "/phonemcp/v1/chat",
                    body = body,
                    connectMs = 5_000,
                    readMs = timeoutS * 1000L + 5_000L,
                )
            return PhoneMcpChatLogic.parseChatResponse(raw)
        }

        /** Launch Magisk agent_runner for a UI command (same path as voice wake). */
        fun wake(text: String, source: String = "native-chat"): WakeResult {
            val body = PhoneMcpChatLogic.wakeRequestJson(text, source)
            val raw =
                http(
                    method = "POST",
                    path = "/phonemcp/v1/wake",
                    body = body,
                    connectMs = 5_000,
                    // Must exceed ChatHttpServer handleWake su timeout (35s).
                    readMs = 45_000,
                )
            return PhoneMcpChatLogic.parseWakeResponse(raw)
        }

        fun status(): LoopbackStatus {
            val raw = http("GET", "/phonemcp/v1/status", body = null, connectMs = 5_000, readMs = 12_000)
            return PhoneMcpChatLogic.parseStatus(raw)
        }

        fun ensureStt() {
            http("POST", "/stt/ensure", body = "{}", connectMs = 5_000, readMs = 30_000)
        }

        private fun http(
            method: String,
            path: String,
            body: String?,
            connectMs: Int,
            readMs: Long,
        ): String {
            val url = URL("http://${ChatHttpServer.HOST}:${ChatHttpServer.PORT}$path")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = connectMs
                conn.readTimeout = readMs.toInt().coerceAtLeast(connectMs)
                conn.setRequestProperty("Accept", "application/json")
                // Avoid keep-alive reuse if a prior response left the socket misaligned.
                conn.setRequestProperty("Connection", "close")
                if (body != null) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setRequestProperty("Content-Length", bytes.size.toString())
                    conn.outputStream.use { it.write(bytes) }
                }
                val stream =
                    try {
                        if (conn.responseCode in 200..399) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                    } catch (_: Exception) {
                        null
                    }
                return stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            } finally {
                conn.disconnect()
            }
        }
    }
