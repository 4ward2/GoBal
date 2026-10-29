package com.danielealbano.androidremotecontrolmcp.services.chat

import android.util.Base64
import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModelGate
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModels
import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderPresets
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteChats
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** One conversation turn on its way to a model: text plus image files (shown to vision models). */
data class ChatMessage(
    val role: String,
    val text: String,
    val images: List<File> = emptyList(),
)

/** A resolved model choice: its provider, why it is not ready, and how to fit a conversation to it. */
private data class Prepared(
    val provider: ModelProvider?,
    val notReady: String?,
    val shape: (List<ChatMessage>) -> List<ChatMessage>,
    /** Seconds to wait for the reply: a phone CPU reads a long prompt far slower than a cloud service. */
    val timeout: (Int) -> Int = { it },
)

/** What an HTTP call to a provider returned. */
data class HttpReply(
    val code: Int,
    val body: String,
)

/** A streamed POST: where it goes and how long a silent connection is waited on. */
data class StreamRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    val timeoutMs: Int,
)

/** Plain HTTP for the providers; an interface so the gateway can be tested without a network. */
interface ProviderHttp {
    fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Int,
    ): HttpReply

    fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): HttpReply

    /**
     * POSTs and hands every response line to [onLine] as it arrives (server-sent events).
     * Returns the status code; a non-2xx body is collected and returned as [HttpReply.body].
     */
    fun stream(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Int,
        onLine: (String) -> Unit,
    ): HttpReply

    /**
     * [stream], after handing [onOpen] a function that aborts the request from another thread:
     * a blocked read cannot be interrupted, so stopping a reply closes its connection instead.
     */
    fun streamAbortable(
        request: StreamRequest,
        onOpen: (abort: () -> Unit) -> Unit,
        onLine: (String) -> Unit,
    ): HttpReply = stream(request.url, request.headers, request.body, request.timeoutMs, onLine)
}

@Singleton
class UrlConnectionProviderHttp
    @Inject
    constructor() : ProviderHttp {
        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Int,
        ): HttpReply = exchange("POST", url, headers, body, timeoutMs)

        override fun get(
            url: String,
            headers: Map<String, String>,
            timeoutMs: Int,
        ): HttpReply = exchange("GET", url, headers, null, timeoutMs)

        override fun stream(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Int,
            onLine: (String) -> Unit,
        ): HttpReply = streamAbortable(StreamRequest(url, headers, body, timeoutMs), {}, onLine)

        override fun streamAbortable(
            request: StreamRequest,
            onOpen: (abort: () -> Unit) -> Unit,
            onLine: (String) -> Unit,
        ): HttpReply {
            val conn = URL(request.url).openConnection() as HttpURLConnection
            onOpen { conn.disconnect() }
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = CONNECT_MS
                conn.readTimeout = request.timeoutMs
                conn.setRequestProperty("Accept", "text/event-stream")
                request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in HTTP_OK_RANGE) {
                    val text = (conn.errorStream ?: conn.inputStream)?.use { it.readBytes().decodeToString() }.orEmpty()
                    return HttpReply(code, text)
                }
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        onLine(line)
                        line = reader.readLine()
                    }
                }
                return HttpReply(code, "")
            } finally {
                conn.disconnect()
            }
        }

        private fun exchange(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int,
        ): HttpReply {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = CONNECT_MS
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (body != null) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(bytes) }
                }
                val code = conn.responseCode
                val stream = if (code in HTTP_OK_RANGE) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
                return HttpReply(code, text)
            } finally {
                conn.disconnect()
            }
        }

        private companion object {
            const val CONNECT_MS = 15_000
            val HTTP_OK_RANGE = 200..399
        }
    }

/**
 * An agent on a computer keeps its own conversation: it is sent the newest thing the user said
 * and nothing else (no system prompt, no memory, no earlier turns from this phone).
 */
private fun lastSaid(messages: List<ChatMessage>): String {
    val newest = messages.lastOrNull { it.role == "user" }
    return newest?.text.orEmpty()
}

/** The pictures attached to what the user said last; documents already travel as text inside it. */
private fun lastPictures(messages: List<ChatMessage>): List<File> {
    val newest = messages.lastOrNull { it.role == "user" }
    return newest?.images.orEmpty()
}

/**
 * Runs the blocking [block]; when this coroutine is cancelled meanwhile (the user stopped the
 * reply) the abort function [block] registered is called from another thread.
 */
private suspend fun <T> abortable(block: (register: (() -> Unit) -> Unit) -> T): T =
    coroutineScope {
        val abort = AtomicReference<(() -> Unit)?>(null)
        val watcher =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    abort.getAndSet(null)?.invoke()
                }
            }
        try {
            block { stopIt ->
                abort.set(stopIt)
                // Cancelled before there was anything to abort: the watcher is gone, so abort here.
                if (!isActive) abort.getAndSet(null)?.invoke()
            }
        } finally {
            abort.set(null)
            watcher.cancel()
        }
    }

/**
 * Sends a conversation to whichever model the contact chose: a provider from 设置 → 服务商, or the
 * phone's own `provider.json` through the loopback server when none is chosen (or it is missing).
 */
@Singleton
class ModelGateway
    @Inject
    constructor(
        private val loopback: PhoneMcpLoopbackClient,
        private val settings: AssistSettingsRepository,
        private val http: ProviderHttp,
        private val local: LocalModelGate,
        private val remote: RemoteChats,
    ) {
        suspend fun chat(
            messages: List<ChatMessage>,
            choice: ModelChoice,
            timeoutS: Int = DEFAULT_TIMEOUT_S,
        ): ChatApiResult {
            if (remote.handles(choice)) return remote.talk(choice, lastSaid(messages), lastPictures(messages)) {}
            val prepared = prepare(choice)
            val provider = prepared.provider
            return when {
                prepared.notReady != null -> ChatApiResult(ok = false, error = prepared.notReady)
                provider == null -> loopback.chat(prepared.shape(messages).map { it.role to flatten(it) }, timeoutS)
                else -> viaProvider(provider, choice.model, prepared.shape(messages), prepared.timeout(timeoutS))
            }
        }

        /**
         * Like [chat] but the reply arrives as text deltas, so a voice can start reading the first
         * sentence while the rest is still being written. The on-device path cannot stream: its
         * whole reply is emitted once. Errors surface as [ChatApiResult] from [onDone].
         */
        suspend fun chatStream(
            messages: List<ChatMessage>,
            choice: ModelChoice,
            timeoutS: Int = DEFAULT_TIMEOUT_S,
            onDelta: (String) -> Unit,
        ): ChatApiResult {
            if (remote.handles(choice)) return remote.talk(choice, lastSaid(messages), lastPictures(messages), onDelta)
            val prepared = prepare(choice)
            val provider = prepared.provider
            return when {
                prepared.notReady != null -> {
                    ChatApiResult(ok = false, error = prepared.notReady)
                }

                provider == null -> {
                    val whole = loopback.chat(prepared.shape(messages).map { it.role to flatten(it) }, timeoutS)
                    if (whole.ok) onDelta(whole.content)
                    whole
                }

                else -> {
                    val fitted = prepared.shape(messages)
                    streamViaProvider(provider, choice.model, fitted, prepared.timeout(timeoutS), onDelta)
                }
            }
        }

        private suspend fun streamViaProvider(
            provider: ModelProvider,
            model: String,
            messages: List<ChatMessage>,
            timeoutS: Int,
            onDelta: (String) -> Unit,
        ): ChatApiResult {
            val chosen = model.ifBlank { provider.models.firstOrNull().orEmpty() }
            if (chosen.isBlank()) return ChatApiResult(ok = false, error = "服务商「${provider.name}」没有可用模型")
            val plain = ProviderRequests.body(provider.kind, chosen, messages.map { toApi(it) })
            val body = StreamingReplies.streamingBody(plain)
            val collected = StringBuilder()
            val thought = StringBuilder()
            val request =
                StreamRequest(
                    ProviderRequests.chatUrl(provider.kind, provider.baseUrl),
                    ProviderRequests.headers(provider.kind, provider.apiKey),
                    body,
                    timeoutS * MS_PER_S,
                )
            val sent =
                abortable { onOpen ->
                    runCatching {
                        http.streamAbortable(request, onOpen) { line ->
                            thought.append(StreamingReplies.reasoning(provider.kind, line))
                            val delta = StreamingReplies.delta(provider.kind, line)
                            if (!delta.isNullOrEmpty()) {
                                collected.append(delta)
                                onDelta(delta)
                            }
                        }
                    }
                }
            return sent.fold(
                onSuccess = { reply ->
                    when {
                        reply.code !in OK_RANGE -> {
                            val parsed = ProviderRequests.parseReply(provider.kind, reply.body)
                            ChatApiResult(ok = false, error = "HTTP ${reply.code}: ${parsed.error}")
                        }

                        collected.isEmpty() -> {
                            ChatApiResult(ok = false, error = "empty")
                        }

                        else -> {
                            ChatApiResult(ok = true, content = collected.toString(), reasoning = thought.toString())
                        }
                    }
                },
                onFailure = { e -> ChatApiResult(ok = false, error = e.message ?: "network error") },
            )
        }

        /**
         * Asks a provider for the models it can chat with, and each one's context window where the
         * service states it. Speech, embedding and image models are left out. Where the model list
         * is public (it would answer any key), the service's key-only endpoint must accept the key
         * first, so a success here always means the key works.
         */
        suspend fun listModels(provider: ModelProvider): Result<List<ModelInfo>> =
            runCatching {
                val headers = ProviderRequests.headers(provider.kind, provider.apiKey)
                val keyOnly = ProviderPresets.byHost(provider.baseUrl)?.keyCheckUrl?.takeIf { it.isNotBlank() }
                keyOnly?.let { url ->
                    val reply = http.get(url, headers, LIST_TIMEOUT_MS)
                    // 404 / 405: the key-only endpoint is gone; the listing below still has to work.
                    val judged = reply.code in OK_RANGE || reply.code in NO_SUCH_ENDPOINT
                    check(judged) { "HTTP ${reply.code}: ${reply.body.take(ERROR_PREVIEW)}" }
                }
                val url = ProviderRequests.modelsUrl(provider.kind, provider.baseUrl)
                val reply = http.get(url, headers, LIST_TIMEOUT_MS)
                check(reply.code in OK_RANGE) { "HTTP ${reply.code}: ${reply.body.take(ERROR_PREVIEW)}" }
                ModelCatalog.chatOnly(ModelCatalog.parse(reply.body), provider.kind)
            }

        /**
         * Whether the provider answers with this key. `GET …/models` is the cheap check; services
         * without that endpoint (404 / 405) get a one-word chat instead, so a working key still counts.
         */
        suspend fun probe(provider: ModelProvider): Boolean {
            // The embedded model counts as there when installed; probing must not load gigabytes.
            if (LocalModels.isLocal(provider)) return provider.models.any { local.installed(it) }
            val headers = ProviderRequests.headers(provider.kind, provider.apiKey)
            // A public model list would answer any key; use the service's key-only endpoint instead.
            val keyOnly = ProviderPresets.byHost(provider.baseUrl)?.keyCheckUrl?.takeIf { it.isNotBlank() }
            val url = keyOnly ?: ProviderRequests.modelsUrl(provider.kind, provider.baseUrl)
            val listing = runCatching { http.get(url, headers, LIST_TIMEOUT_MS) }.getOrNull()
            return when {
                listing == null -> {
                    false
                }

                listing.code in OK_RANGE -> {
                    true
                }

                listing.code !in NO_SUCH_ENDPOINT -> {
                    false
                }

                else -> {
                    val ping = listOf(ChatMessage("user", PING_TEXT))
                    viaProvider(provider, provider.models.firstOrNull().orEmpty(), ping, PING_TIMEOUT_S).ok
                }
            }
        }

        /**
         * The provider [choice] names (null for the phone's own path), after waking the embedded
         * model when that is the one chosen, with why it could not be woken and how to fit the
         * conversation to it: the embedded models have small windows and some cannot see pictures
         * (a picture's OCR text is already in the message, so dropping the image loses little).
         */
        private suspend fun prepare(choice: ModelChoice): Prepared {
            val providers = settings.providers.first()
            val provider = if (choice.onDevice) null else providers.firstOrNull { it.id == choice.providerId }
            val onPhone = provider?.takeIf { LocalModels.isLocal(it) }
            val model = onPhone?.let { choice.model.ifBlank { it.models.firstOrNull().orEmpty() } }
            val notReady = model?.let { local.wake(it) }
            val spec = model?.takeIf { notReady == null }?.let { local.spec(it) }
            // The whole history is stored; what is sent is cut to the model's window, oldest turns first.
            val named = choice.model.ifBlank { provider?.models?.firstOrNull().orEmpty() }
            val window = spec?.contextSize ?: ContextBudget.window(provider, named)
            val blind = spec != null && spec.mmproj == null
            val shape: (List<ChatMessage>) -> List<ChatMessage> = { messages ->
                val seen = if (blind) messages.map { it.copy(images = emptyList()) } else messages
                ContextBudget.fit(seen, window)
            }
            val timeout: (Int) -> Int = if (onPhone == null) { s -> s } else { s -> maxOf(s, LOCAL_TIMEOUT_S) }
            return Prepared(provider, notReady, shape, timeout)
        }

        private fun viaProvider(
            provider: ModelProvider,
            model: String,
            messages: List<ChatMessage>,
            timeoutS: Int,
        ): ChatApiResult {
            val chosen = model.ifBlank { provider.models.firstOrNull().orEmpty() }
            if (chosen.isBlank()) return ChatApiResult(ok = false, error = "服务商「${provider.name}」没有可用模型")
            val body = ProviderRequests.body(provider.kind, chosen, messages.map { toApi(it) })
            val sent =
                runCatching {
                    http.post(
                        ProviderRequests.chatUrl(provider.kind, provider.baseUrl),
                        ProviderRequests.headers(provider.kind, provider.apiKey),
                        body,
                        timeoutS * MS_PER_S,
                    )
                }
            return sent.fold(
                onSuccess = { reply ->
                    val parsed = ProviderRequests.parseReply(provider.kind, reply.body)
                    if (reply.code in OK_RANGE || parsed.ok) {
                        parsed
                    } else {
                        ChatApiResult(ok = false, error = "HTTP ${reply.code}: ${parsed.error}")
                    }
                },
                onFailure = { e -> ChatApiResult(ok = false, error = e.message ?: "network error") },
            )
        }

        private fun toApi(message: ChatMessage): ApiMessage =
            ApiMessage(
                role = message.role,
                text = message.text,
                images = message.images.mapNotNull { encode(it) },
            )

        private fun encode(file: File): ImageData? {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
            val mime = if (file.extension.equals("png", ignoreCase = true)) "image/png" else "image/jpeg"
            return ImageData(mime, Base64.encodeToString(bytes, Base64.NO_WRAP))
        }

        /** The on-device path is text only: images become a placeholder the model can acknowledge. */
        private fun flatten(message: ChatMessage): String =
            if (message.images.isEmpty()) {
                message.text
            } else {
                (listOf(message.text) + message.images.map { "[图片：${it.name}]" })
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            }

        private companion object {
            const val DEFAULT_TIMEOUT_S = 45

            /** The on-device model may read a 2 000-token prompt for over a minute on an older phone. */
            const val LOCAL_TIMEOUT_S = 300
            const val MS_PER_S = 1000
            const val LIST_TIMEOUT_MS = 20_000
            const val ERROR_PREVIEW = 200
            const val PING_TEXT = "hi"
            const val PING_TIMEOUT_S = 20
            val OK_RANGE = 200..299
            val NO_SUCH_ENDPOINT = setOf(404, 405)
        }
    }
