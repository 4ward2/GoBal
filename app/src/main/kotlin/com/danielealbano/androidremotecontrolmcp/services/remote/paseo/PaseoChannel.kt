package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.remote.ChannelHooks
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkDiagnosis
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkFailure
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkState
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkStatus
import com.danielealbano.androidremotecontrolmcp.services.remote.PairedComputer
import com.danielealbano.androidremotecontrolmcp.services.remote.Route
import com.danielealbano.androidremotecontrolmcp.services.remote.RoutePolicy
import com.danielealbano.androidremotecontrolmcp.services.remote.SecureChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64

/** Builds the WebSocket request for a route. Pure. */
object PaseoRoutes {
    const val HELLO_TYPE = "e2ee_hello"
    const val READY_TYPE = "e2ee_ready"
    private const val BEARER = "paseo.bearer."

    fun url(
        computer: PairedComputer,
        route: Route,
    ): String {
        val scheme = if (route.tls) "wss" else "ws"
        val base = "$scheme://${route.endpoint}/ws"
        val relayed = "$base?serverId=${computer.peerId}&role=client&v=${computer.protocol}"
        return if (route.kind == Route.RELAY) relayed else base
    }

    /** The daemon's password travels as a WebSocket subprotocol; only a direct route has one. */
    fun subprotocol(
        computer: PairedComputer,
        route: Route,
    ): String? = computer.secret.takeIf { it.isNotEmpty() && route.kind == Route.DIRECT }?.let { BEARER + it }

    /** The one plaintext frame of a relay connection: this side's public key for the session. */
    fun hello(publicKey: ByteArray): String =
        buildJsonObject {
            put("type", HELLO_TYPE)
            put("key", Base64.getEncoder().encodeToString(publicKey))
        }.toString()

    /** The handshake type of a plaintext frame, "" when it is not one. */
    fun handshakeType(frame: String): String {
        if (!frame.trimStart().startsWith("{")) return ""
        val parsed = runCatching { Json.parseToJsonElement(frame).jsonObject }.getOrNull()
        val type = (parsed?.get("type") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return type.takeIf { it == HELLO_TYPE || it == READY_TYPE }.orEmpty()
    }
}

/**
 * The channel to a Paseo daemon. Through the relay it is end-to-end encrypted: a key pair made
 * for each connection, the daemon's key from the pairing, NaCl box on every frame after the
 * handshake; the relay carries what it cannot read. A direct route is a plain WebSocket and is
 * as private as the network under it (see [RoutePolicy]).
 *
 * It keeps trying while the computer is merely away, and stops for good when it is refused.
 */
class PaseoChannel(
    private val computer: PairedComputer,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val vpnActive: () -> Boolean,
    private val hooks: ChannelHooks,
) : SecureChannel {
    private val _status = MutableStateFlow(LinkStatus(LinkState.CONNECTING))
    override val status: StateFlow<LinkStatus> = _status.asStateFlow()
    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = BUFFERED)
    override val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    @Volatile
    private var live: Attempt? = null
    private val loop: Job = scope.launch { keepConnected() }

    override fun send(message: String): Boolean {
        val open = live?.takeIf { _status.value.state == LinkState.ONLINE }
        return open?.send(message) ?: false
    }

    override fun restart() {
        live?.close()
    }

    override fun close() {
        loop.cancel()
        live?.close()
        live = null
    }

    private suspend fun keepConnected() {
        val routes = computer.routesToTry()
        val barred = routes.firstNotNullOfOrNull(RoutePolicy::refusal)
        if (routes.isEmpty() || barred != null) {
            _status.value = LinkStatus(LinkState.REJECTED, barred ?: LinkDiagnosis.NOT_PAIRED)
            return
        }
        var failures = 0
        var index = 0
        while (scope.isActive && _status.value.state != LinkState.REJECTED) {
            val attempt = Attempt(routes[index % routes.size])
            live = attempt
            val failure = attempt.run()
            live = null
            // An attempt that had got through earns a prompt retry; a string of failures backs off.
            failures = if (attempt.wasOnline) 0 else failures + 1
            val refused = LinkDiagnosis.isRefusal(failure) || attempt.undecipherable
            val reason = if (attempt.undecipherable) LinkDiagnosis.NOT_PAIRED else LinkDiagnosis.explain(failure)
            _status.value = LinkStatus(if (refused) LinkState.REJECTED else LinkState.OFFLINE, reason)
            index++
            // When to try again is not this channel's to decide: all the app's connections are
            // paced together, and none of them tries while the phone has no network.
            if (!refused) hooks.reconnects.pause(failures)
        }
    }

    /** One connection over one route, from dialling to its end. */
    private inner class Attempt(
        private val route: Route,
    ) : WebSocketListener() {
        private val ended = CompletableDeferred<LinkFailure>()
        private val keys = NaclBox.keyPair()
        private val shared: ByteArray? =
            computer.peerKey.takeIf { route.kind == Route.RELAY }?.let { key ->
                runCatching { NaclBox.sharedKey(keys.secret, Base64.getDecoder().decode(key)) }.getOrNull()
            }
        private var socket: WebSocket? = null
        private var knocking: Job? = null

        @Volatile
        var wasOnline = false
            private set

        /** The computer answered with frames this pairing's key cannot open. */
        @Volatile
        var undecipherable = false
            private set

        suspend fun run(): LinkFailure {
            _status.value = LinkStatus(LinkState.CONNECTING, _status.value.reason)
            val request = Request.Builder().url(PaseoRoutes.url(computer, route))
            PaseoRoutes.subprotocol(computer, route)?.let { request.header("Sec-WebSocket-Protocol", it) }
            socket = http.newWebSocket(request.build(), this)
            // A computer that does not answer in time is treated as away, not waited on for ever.
            val watchdog =
                scope.launch {
                    delay(ANSWER_TIMEOUT_MS)
                    if (!wasOnline) socket?.cancel().also { fail(null, 0) }
                }
            return try {
                ended.await()
            } finally {
                watchdog.cancel()
                knocking?.cancel()
                socket?.cancel()
            }
        }

        fun send(message: String): Boolean {
            val frame = if (shared == null) message else NaclBox.sealFrame(shared, message)
            return socket?.send(frame) ?: false
        }

        fun close() = fail(null, 0).also { socket?.close(NORMAL, "bye") }

        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            if (shared == null) {
                online()
            } else {
                // The daemon may join the relay session a moment after us: knock until it answers.
                knocking =
                    scope.launch {
                        while (isActive && !wasOnline) {
                            webSocket.send(PaseoRoutes.hello(keys.public))
                            delay(KNOCK_MS)
                        }
                    }
            }
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            val handshake = if (shared == null) "" else PaseoRoutes.handshakeType(text)
            when {
                handshake == PaseoRoutes.READY_TYPE -> online()
                handshake.isNotEmpty() -> Unit
                else -> deliver(if (shared == null) text else NaclBox.openFrame(shared, text))
            }
        }

        /** Hands on what the computer said; a frame this pairing's key cannot open ends the connection. */
        private fun deliver(plain: String?) {
            if (plain == null) {
                undecipherable = true
                socket?.close(POLICY, "cannot decrypt")
                fail(null, 0)
            } else {
                hooks.heard()
                _incoming.tryEmit(plain)
            }
        }

        private fun online() {
            wasOnline = true
            _status.value = LinkStatus(LinkState.ONLINE)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            Log.i(LINK_TAG, "closed by the other side: $code")
            ended.complete(LinkFailure(route, null, 0, vpnActive()))
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            Log.i(LINK_TAG, "connection failed: ${t.javaClass.simpleName} ${response?.code ?: 0}")
            fail(t, response?.code ?: 0)
        }

        private fun fail(
            error: Throwable?,
            httpCode: Int,
        ) {
            ended.complete(LinkFailure(route, error, httpCode, vpnActive()))
        }
    }

    private companion object {
        const val BUFFERED = 256
        const val ANSWER_TIMEOUT_MS = 20_000L
        const val KNOCK_MS = 1_000L
        const val NORMAL = 1000
        const val POLICY = 1008
    }
}

private const val LINK_TAG = "MCP:Paseo"
