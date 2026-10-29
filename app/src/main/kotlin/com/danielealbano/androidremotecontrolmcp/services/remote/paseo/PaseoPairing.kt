package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import com.danielealbano.androidremotecontrolmcp.services.remote.PairedComputer
import com.danielealbano.androidremotecontrolmcp.services.remote.Pairing
import com.danielealbano.androidremotecontrolmcp.services.remote.Route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

/**
 * Reads a Paseo pairing: the link behind the QR code its desktop app and `paseo daemon pair`
 * show (`https://app.paseo.sh/#offer=<base64url JSON>`, relay only), or the daemon's own address
 * on a network the phone shares with it (`tcp://host:port[?ssl=true&password=…]`, or `host:port`).
 * Pure.
 */
object PaseoPairing : Pairing {
    const val BACKEND = "paseo"

    /** The offer format (`v`) and the relay protocol this app speaks. */
    const val PROTOCOL = 2
    const val NOT_A_LINK = "这不是配对链接。请在电脑上的 Paseo 里打开「配对设备」，复制链接或扫二维码。"
    const val UNSUPPORTED = "这个配对链接的版本本应用还不认识，请更新应用。"
    private const val OFFER_MARK = "#offer="
    private const val KEY_BYTES = 32
    private const val MAX_PORT = 65_535
    private val json = Json { ignoreUnknownKeys = true }
    private val HOST_PORT = Regex("""^(\[[0-9a-fA-F:]+]|[A-Za-z0-9._-]+):(\d{1,5})$""")

    override fun read(
        input: String,
        now: Long,
    ): Result<PairedComputer> {
        val text = input.trim()
        val offer = text.substringAfter(OFFER_MARK, "").trim()
        return runCatching {
            val found =
                when {
                    offer.isNotEmpty() -> fromOffer(offer)
                    text.startsWith("tcp://") -> fromUri(text)
                    else -> direct(text, tls = false, password = "")
                }
            found.copy(id = UUID.randomUUID().toString(), addedAt = now)
        }
    }

    private fun fromOffer(encoded: String): PairedComputer {
        val decoded = runCatching { Base64.getUrlDecoder().decode(encoded.trimEnd('=')) }.getOrNull()
        val text = decoded?.toString(Charsets.UTF_8)
        val offer = text?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        requireNotNull(offer) { NOT_A_LINK }
        require(offer.int("v") == PROTOCOL) { UNSUPPORTED }
        val relay = offer["relay"] as? JsonObject
        val key = offer.text("daemonPublicKeyB64")
        val keyBytes = runCatching { Base64.getDecoder().decode(key) }.getOrNull()
        val endpoint = relay?.text("endpoint").orEmpty()
        val serverId = offer.text("serverId")
        require(serverId.isNotEmpty() && keyBytes?.size == KEY_BYTES && HOST_PORT.matches(endpoint)) { NOT_A_LINK }
        val route = Route(Route.RELAY, endpoint, relay?.flag("useTls") ?: true)
        return blank().copy(peerId = serverId, peerKey = key, routes = listOf(route))
    }

    private fun fromUri(text: String): PairedComputer {
        val uri = runCatching { URI(text) }.getOrNull()
        requireNotNull(uri?.host) { NOT_A_LINK }
        val query =
            uri.rawQuery
                .orEmpty()
                .split('&')
                .filter { '=' in it }
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        return direct("${uri.host}:${uri.port}", query["ssl"] == "true", query["password"].orEmpty())
    }

    private fun direct(
        endpoint: String,
        tls: Boolean,
        password: String,
    ): PairedComputer {
        val port =
            HOST_PORT
                .matchEntire(endpoint)
                ?.groupValues
                ?.get(2)
                ?.toIntOrNull()
        require(port != null && port in 1..MAX_PORT) { NOT_A_LINK }
        return blank().copy(routes = listOf(Route(Route.DIRECT, endpoint, tls)), secret = password)
    }

    private fun blank() = PairedComputer(id = "", name = "", backend = BACKEND, protocol = PROTOCOL)

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
