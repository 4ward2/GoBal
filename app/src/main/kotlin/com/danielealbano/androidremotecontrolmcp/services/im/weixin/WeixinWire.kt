/*
 * The request and message formats in this file follow the client of the Weixin iLink bot
 * interface published by Tencent as `@tencent-weixin/openclaw-weixin`:
 *
 *   Copyright (C) 2026 Tencent. All rights reserved.
 *   Licensed under the MIT License.
 *
 * This is a port of the protocol to Kotlin, not a copy of that program.
 */

package com.danielealbano.androidremotecontrolmcp.services.im.weixin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** What the login page shows as a QR code, and the handle to ask how the scan went. */
data class WeixinQr(
    /** Opaque; goes back as the `qrcode` query parameter. */
    val handle: String,
    /** A link, to be drawn as a QR code (it is not image data). */
    val content: String,
)

/** Where a login stands. [status] is the service's own word ("wait", "scaned", "confirmed", …). */
data class WeixinQrStatus(
    val status: String,
    val token: String = "",
    val botId: String = "",
    val baseUrl: String = "",
    /** The Weixin user who scanned: the only sender this phone will ever listen to. */
    val userId: String = "",
    val redirectHost: String = "",
)

/** One message as received. */
data class WeixinMessage(
    val from: String,
    /** 1 when a user wrote it, 2 when a bot did. */
    val type: Int,
    /** Not empty for a message from a group. */
    val groupId: String,
    val contextToken: String,
    val createdAt: Long,
    /** What the sender typed (or said, when the service transcribed the voice message). */
    val said: String,
    /** What the message carries besides: a quoted message's words. Read as material, never as an order. */
    val quoted: String,
    /** True when it carries a picture, a file, a video or a voice message without transcript. */
    val hasMedia: Boolean,
)

/** One answer of the long poll. */
data class WeixinUpdates(
    /** 0 when it went well; [WeixinWire.STALE_TOKEN] when the login no longer holds. */
    val code: Int,
    val messages: List<WeixinMessage> = emptyList(),
    /** The cursor to send back next time; "" when the service sent none. */
    val cursor: String = "",
    /** How long the service wants the next poll to wait, 0 when it does not say. */
    val pollMs: Long = 0,
)

/** The requests and answers of the iLink bot interface. Pure. */
object WeixinWire {
    const val LOGIN_BASE = "https://ilinkai.weixin.qq.com"
    const val STALE_TOKEN = -14
    const val USER_MESSAGE = 1
    private const val BOT_MESSAGE = 2
    private const val FINISHED = 2
    private const val TEXT = 1
    private const val BOT_TYPE = "3"

    /** The version of the client this port follows; the service reads it as the protocol level. */
    private const val CHANNEL_VERSION = "2.4.6"
    private val VERSION_PARTS = CHANNEL_VERSION.split('.').map { it.toInt() }
    private const val BYTE = 0xff
    private const val MAJOR_SHIFT = 16
    private const val MINOR_SHIFT = 8
    private const val MAX_KNOWN_TOKENS = 10

    fun qrUrl(): String = "$LOGIN_BASE/ilink/bot/get_bot_qrcode?bot_type=$BOT_TYPE"

    fun statusUrl(
        base: String,
        handle: String,
        code: String,
    ): String {
        val verify = if (code.isEmpty()) "" else "&verify_code=$code"
        return "${base.trimEnd('/')}/ilink/bot/get_qrcode_status?qrcode=$handle$verify"
    }

    fun url(
        base: String,
        endpoint: String,
    ): String = "${base.ifBlank { LOGIN_BASE }.trimEnd('/')}/ilink/bot/$endpoint"

    /** The headers every request carries; a POST adds those of [postHeaders]. */
    fun commonHeaders(): Map<String, String> {
        val (major, minor, patch) = VERSION_PARTS
        val packed = ((major and BYTE) shl MAJOR_SHIFT) or ((minor and BYTE) shl MINOR_SHIFT) or (patch and BYTE)
        return mapOf("iLink-App-Id" to "bot", "iLink-App-ClientVersion" to packed.toString())
    }

    /** [uin] is a fresh random number for every request; [token] is "" before the login. */
    fun postHeaders(
        token: String,
        uin: UInt,
    ): Map<String, String> {
        val wrapped = Base64.getEncoder().encodeToString(uin.toString().toByteArray())
        val auth = if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${token.trim()}")
        val post = mapOf("Content-Type" to "application/json", "AuthorizationType" to "ilink_bot_token")
        return commonHeaders() + post + ("X-WECHAT-UIN" to wrapped) + auth
    }

    /** [known]: the tokens of logins this phone holds already, so that the service does not log one in twice. */
    fun qrBody(known: List<String>): String =
        buildJsonObject {
            putJsonArray("local_token_list") { known.takeLast(MAX_KNOWN_TOKENS).forEach { add(JsonPrimitive(it)) } }
        }.toString()

    fun updatesBody(
        cursor: String,
        agent: String,
    ): String =
        buildJsonObject {
            put("get_updates_buf", cursor)
            baseInfo(agent)
        }.toString()

    /** A text message to [to]. [contextToken] is the one the user's last message carried; it must go back. */
    fun textBody(
        to: String,
        text: String,
        contextToken: String,
        ids: Pair<String, String>,
    ): String =
        buildJsonObject {
            putJsonObject("msg") {
                put("from_user_id", "")
                put("to_user_id", to)
                put("client_id", ids.first)
                put("message_type", BOT_MESSAGE)
                put("message_state", FINISHED)
                put("item_list", buildJsonArray { add(buildJsonObject { textItem(text) }) })
                if (contextToken.isNotEmpty()) put("context_token", contextToken)
            }
            baseInfo(ids.second)
        }.toString()

    private fun kotlinx.serialization.json.JsonObjectBuilder.textItem(text: String) {
        put("type", TEXT)
        putJsonObject("text_item") { put("text", text) }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.baseInfo(agent: String) =
        putJsonObject("base_info") {
            put("channel_version", CHANNEL_VERSION)
            put("bot_agent", agent)
        }
}

/** Reads what the iLink bot interface answers. Pure. */
object WeixinReplies {
    private const val TEXT = 1
    private const val VOICE = 3
    private const val WAIT = "wait"
    private val json = Json { ignoreUnknownKeys = true }

    fun qr(body: String): WeixinQr? {
        val root = parse(body) ?: return null
        val qr = WeixinQr(root.text("qrcode"), root.text("qrcode_img_content"))
        return qr.takeIf { it.handle.isNotEmpty() && it.content.isNotEmpty() }
    }

    /** An answer that cannot be read is "wait": a gateway hiccup must not end a login in progress. */
    fun status(body: String): WeixinQrStatus {
        val root = parse(body) ?: return WeixinQrStatus(WAIT)
        return WeixinQrStatus(
            status = root.text("status").ifEmpty { WAIT },
            token = root.text("bot_token"),
            botId = root.text("ilink_bot_id"),
            baseUrl = root.text("baseurl"),
            userId = root.text("ilink_user_id"),
            redirectHost = root.text("redirect_host"),
        )
    }

    fun updates(body: String): WeixinUpdates? {
        val root = parse(body) ?: return null
        val messages = (root["msgs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::message) }
        val poll = (root["longpolling_timeout_ms"] as? JsonPrimitive)?.longOrNull ?: 0
        return WeixinUpdates(code(root), messages, root.text("get_updates_buf"), poll)
    }

    /** 0 when the service took the message. */
    fun sendResult(body: String): Int = parse(body)?.let(::code) ?: 0

    private fun code(root: JsonObject): Int = root.int("errcode").takeIf { it != 0 } ?: root.int("ret")

    private fun message(node: JsonObject): WeixinMessage {
        val items = (node["item_list"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val text = items.firstOrNull { it.int("type") == TEXT }
        val voice = items.firstOrNull { it.int("type") == VOICE }?.get("voice_item") as? JsonObject
        val transcript = voice?.text("text").orEmpty()
        val typed = (text?.get("text_item") as? JsonObject)?.text("text")
        val carriesMedia = items.any { it.int("type") != TEXT } && transcript.isEmpty()
        return WeixinMessage(
            from = node.text("from_user_id"),
            type = node.int("message_type"),
            groupId = node.text("group_id"),
            contextToken = node.text("context_token"),
            createdAt = (node["create_time_ms"] as? JsonPrimitive)?.longOrNull ?: 0,
            said = (typed ?: transcript).trim(),
            quoted = quoted(text?.get("ref_msg") as? JsonObject),
            hasMedia = carriesMedia,
        )
    }

    /** The words of the message this one quotes: its title and, when it was text, what it said. */
    private fun quoted(ref: JsonObject?): String {
        val inner = (ref?.get("message_item") as? JsonObject)?.get("text_item") as? JsonObject
        return listOfNotNull(ref?.text("title"), inner?.text("text")).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun parse(body: String): JsonObject? = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
}
