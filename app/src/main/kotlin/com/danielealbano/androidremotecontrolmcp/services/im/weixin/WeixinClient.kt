package com.danielealbano.androidremotecontrolmcp.services.im.weixin

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Talks to the iLink bot interface. Every call answers null when the service could not be reached. */
class WeixinClient(
    private val http: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
    /** How this app names itself to the service; read there for its logs only. */
    private val agent: String,
) {
    private val random = SecureRandom()

    suspend fun qr(knownTokens: List<String>): WeixinQr? =
        post(WeixinWire.qrUrl(), "", WeixinWire.qrBody(knownTokens), API_TIMEOUT_MS)?.let(WeixinReplies::qr)

    /** Waits up to the long-poll time for the scan to move on; no answer in time reads as "still waiting". */
    suspend fun status(
        base: String,
        handle: String,
        code: String,
    ): WeixinQrStatus {
        val request = Request.Builder().url(WeixinWire.statusUrl(base, handle, code))
        WeixinWire.commonHeaders().forEach { (name, value) -> request.header(name, value) }
        val body = call(request.build(), POLL_TIMEOUT_MS)
        return WeixinReplies.status(body.orEmpty())
    }

    /** One long poll. Null when the network failed; an empty answer when the poll simply ran out. */
    suspend fun updates(
        account: WeixinAccount,
        pollMs: Long,
    ): WeixinUpdates? {
        val url = WeixinWire.url(account.baseUrl, "getupdates")
        val wait = if (pollMs > 0) pollMs else POLL_TIMEOUT_MS
        val body = post(url, account.token, WeixinWire.updatesBody(account.cursor, agent), wait, quietTimeout = true)
        return if (body == TIMED_OUT) WeixinUpdates(0, cursor = account.cursor) else body?.let(WeixinReplies::updates)
    }

    /** Sends [text] to the account's owner. Returns the service's code: 0 when taken, null when unreachable. */
    suspend fun send(
        account: WeixinAccount,
        text: String,
    ): Int? {
        val id = "brooce-weixin:${System.currentTimeMillis()}-${"%08x".format(random.nextInt())}"
        val body = WeixinWire.textBody(account.ownerId, text, account.contextToken, id to agent)
        val answer = post(WeixinWire.url(account.baseUrl, "sendmessage"), account.token, body, API_TIMEOUT_MS)
        return answer?.let(WeixinReplies::sendResult)
    }

    private suspend fun post(
        url: String,
        token: String,
        body: String,
        timeoutMs: Long,
        quietTimeout: Boolean = false,
    ): String? {
        val request = Request.Builder().url(url).post(body.toRequestBody(JSON))
        val headers = WeixinWire.postHeaders(token, random.nextInt().toUInt())
        headers.forEach { (name, value) -> request.header(name, value) }
        return call(request.build(), timeoutMs, quietTimeout)
    }

    private suspend fun call(
        request: Request,
        timeoutMs: Long,
        quietTimeout: Boolean = false,
    ): String? {
        val client = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        return runInterruptible(ioDispatcher) {
            val answer = runCatching { client.newCall(request).execute().use { it.body?.string().orEmpty() } }
            val failure = answer.exceptionOrNull()
            // A poll that ran its time without news is how a long poll ends, not a failure.
            val ranOut = failure is InterruptedIOException && quietTimeout
            if (failure != null && failure !is IOException) throw failure
            if (ranOut) TIMED_OUT else answer.getOrNull()
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val TIMED_OUT = "\u0000timeout"
        const val POLL_TIMEOUT_MS = 35_000L
        const val API_TIMEOUT_MS = 15_000L
    }
}
