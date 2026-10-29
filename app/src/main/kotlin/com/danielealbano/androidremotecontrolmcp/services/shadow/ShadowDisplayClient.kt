package com.danielealbano.androidremotecontrolmcp.services.shadow

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the PhoneMCP shadow display daemon (`magisk/shadow-display`).
 *
 * The daemon runs outside this app, as the shell uid, because launching another app's
 * activity onto a virtual display the caller does not own is refused by the system for
 * normal apps. This app never gains any privilege from it: the daemon checks the peer uid
 * of every connection and only executes a fixed set of commands.
 */
interface ShadowDisplayClient {
    /**
     * Sends [cmd] with [params] and returns the daemon's reply object.
     * Fails with [ShadowDisplayException] when the daemon is unreachable or answers `ok:false`.
     */
    suspend fun request(
        cmd: String,
        params: Map<String, JsonElement> = emptyMap(),
    ): Result<JsonObject>
}

class ShadowDisplayException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** One request/response round trip over the daemon socket. */
fun interface ShadowTransport {
    @Throws(IOException::class)
    fun exchange(line: String): String?
}

/** Line protocol shared by the client and its tests. */
object ShadowProtocol {
    const val SOCKET_NAME = "phonemcp_shadow"

    fun encode(
        cmd: String,
        params: Map<String, JsonElement>,
    ): String {
        require(cmd.isNotBlank()) { "cmd must not be blank" }
        val obj =
            buildJsonObject {
                put("cmd", JsonPrimitive(cmd))
                params.forEach { (k, v) -> if (k != "cmd") put(k, v) }
            }
        return obj.toString()
    }

    fun decode(line: String?): Result<JsonObject> {
        val obj =
            line
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val ok = (obj?.get("ok") as? JsonPrimitive)?.booleanOrNull == true
        val error = (obj?.get("error") as? JsonPrimitive)?.contentOrNull ?: "unknown error"
        return when {
            line.isNullOrBlank() -> {
                Result.failure(ShadowDisplayException("shadow display daemon closed the connection"))
            }

            obj == null -> {
                Result.failure(ShadowDisplayException("malformed reply from shadow display daemon"))
            }

            !ok -> {
                Result.failure(ShadowDisplayException(error))
            }

            else -> {
                Result.success(obj)
            }
        }
    }
}

class LocalSocketShadowTransport
    @Inject
    constructor() : ShadowTransport {
        override fun exchange(line: String): String? =
            LocalSocket().use { socket ->
                socket.connect(
                    LocalSocketAddress(ShadowProtocol.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT),
                )
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val out = socket.outputStream
                out.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
                out.flush()
                socket.inputStream.bufferedReader(StandardCharsets.UTF_8).readLine()
            }

        companion object {
            // "open" waits for `am start`, which can take several seconds on a cold app start.
            private const val SOCKET_TIMEOUT_MS = 45_000
        }
    }

@Singleton
class ShadowDisplayClientImpl
    @Inject
    constructor(
        private val transport: LocalSocketShadowTransport,
    ) : ShadowDisplayClient {
        private val delegate = TransportShadowDisplayClient(transport)

        override suspend fun request(
            cmd: String,
            params: Map<String, JsonElement>,
        ): Result<JsonObject> = delegate.request(cmd, params)
    }

/** Transport-agnostic implementation; [ShadowDisplayClientImpl] wires in the real socket. */
class TransportShadowDisplayClient(
    private val transport: ShadowTransport,
) : ShadowDisplayClient {
    override suspend fun request(
        cmd: String,
        params: Map<String, JsonElement>,
    ): Result<JsonObject> =
        withContext(Dispatchers.IO) {
            val line = ShadowProtocol.encode(cmd, params)
            val reply =
                try {
                    transport.exchange(line)
                } catch (e: IOException) {
                    return@withContext Result.failure(
                        ShadowDisplayException(
                            "shadow display daemon is not running (install it with " +
                                "magisk/install-shadow-display.ps1): ${e.message}",
                            e,
                        ),
                    )
                }
            ShadowProtocol.decode(reply)
        }
}
