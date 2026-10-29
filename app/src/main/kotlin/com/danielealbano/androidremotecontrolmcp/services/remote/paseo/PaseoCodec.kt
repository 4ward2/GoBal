package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import com.danielealbano.androidremotecontrolmcp.services.remote.NewAgent
import com.danielealbano.androidremotecontrolmcp.services.remote.PermissionReply
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteAgent
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteEvent
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteItem
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteMode
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteModel
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What this app sends a daemon. Field names and shapes are the daemon's (`packages/protocol`). Pure. */
object PaseoRequests {
    private const val PROTOCOL_VERSION = 1
    private const val PAGE = 200
    private const val TAIL = 200

    /** The first frame on every connection. */
    fun hello(
        clientId: String,
        appVersion: String,
    ): String =
        buildJsonObject {
            put("type", "hello")
            put("clientId", clientId)
            put("clientType", "mobile")
            put("protocolVersion", PROTOCOL_VERSION)
            put("appVersion", appVersion)
            // Only the conversations this app opens are streamed to it, not every agent's.
            putJsonObject("capabilities") { put("selective_agent_timeline", true) }
        }.toString()

    /**
     * Lists the agents and asks for `agent_update` pushes from then on: without `subscribe` the
     * daemon never says when an agent starts or stops. Each listing replaces the previous
     * subscription (one slot per connection for clients without owned subscriptions).
     */
    fun listAgents(requestId: String): String {
        val fields: JsonObjectBuilder.() -> Unit = {
            putJsonObject("page") { put("limit", PAGE) }
            putJsonObject("subscribe") {}
        }
        return session("fetch_agents_request", requestId, fields)
    }

    /** Which agents' conversations to stream; replaces the previous choice. */
    fun watch(
        requestId: String,
        agentIds: List<String>,
    ): String =
        session("agent.timeline.set_subscription.request", requestId) {
            putJsonArray("agentIds") { agentIds.forEach { add(JsonPrimitive(it)) } }
        }

    fun createAgent(
        requestId: String,
        spec: NewAgent,
    ): String =
        session("create_agent_request", requestId) {
            putJsonObject("config") {
                put("provider", spec.provider)
                put("cwd", spec.cwd)
                if (spec.model.isNotBlank()) put("model", spec.model)
                if (spec.title.isNotBlank()) put("title", spec.title)
                if (spec.mode.isNotBlank()) put("modeId", spec.mode)
            }
            putJsonObject("labels") {}
        }

    fun send(
        requestId: String,
        agentId: String,
        text: String,
        messageId: String,
    ): String =
        session("send_agent_message_request", requestId) {
            put("agentId", agentId)
            put("text", text)
            put("messageId", messageId)
        }

    fun cancel(
        requestId: String,
        agentId: String,
    ): String = session("cancel_agent_request", requestId) { put("agentId", agentId) }

    fun archive(
        requestId: String,
        agentId: String,
    ): String = session("archive_agent_request", requestId) { put("agentId", agentId) }

    /** The newest part of an agent's conversation, as complete messages. */
    fun timeline(
        requestId: String,
        agentId: String,
    ): String =
        session("fetch_agent_timeline_request", requestId) {
            put("agentId", agentId)
            put("direction", "tail")
            put("limit", TAIL)
            put("projection", "projected")
        }

    /** The user's answer to a permission request. Never sent without the user having tapped it. */
    fun answer(
        agentId: String,
        permissionId: String,
        reply: PermissionReply,
    ): String =
        buildJsonObject {
            put("type", "session")
            putJsonObject("message") {
                put("type", "agent_permission_response")
                put("agentId", agentId)
                put("requestId", permissionId)
                putJsonObject("response") {
                    put("behavior", if (reply.allow) "allow" else "deny")
                    reply.choiceId?.let { put("selectedActionId", it) }
                    // Only a refusal carries words for the agent (AgentPermissionResponseSchema).
                    reply.message?.takeIf { !reply.allow && it.isNotBlank() }?.let { put("message", it) }
                }
            }
        }.toString()

    private fun session(
        type: String,
        requestId: String,
        fields: JsonObjectBuilder.() -> Unit,
    ): String =
        buildJsonObject {
            put("type", "session")
            putJsonObject("message") {
                put("type", type)
                put("requestId", requestId)
                fields()
            }
        }.toString()
}

/** The question that only asks for an answer. Pure. */
object PaseoPing {
    /** The daemon answers at once and keeps no lease for it, so how often it is asked is ours to choose. */
    fun request(
        requestId: String,
        now: Long,
    ): String =
        buildJsonObject {
            put("type", "session")
            putJsonObject("message") {
                put("type", "ping")
                put("requestId", requestId)
                put("clientSentAt", now)
            }
        }.toString()
}

/** A frame from the daemon, read. */
sealed interface PaseoFrame {
    /** Who the daemon is; the first thing it says. */
    data class ServerInfo(
        val serverId: String,
        val hostname: String,
        val version: String,
    ) : PaseoFrame

    /** Something that happened, for whoever is watching. */
    data class Live(
        val event: RemoteEvent,
    ) : PaseoFrame

    /** The answer to the request [requestId]; [error] is "" when it went well. */
    data class Reply(
        val requestId: String,
        val error: String = "",
        val agents: List<RemoteAgent> = emptyList(),
        val agent: RemoteAgent? = null,
        val items: List<RemoteItem> = emptyList(),
        val providers: List<RemoteProvider> = emptyList(),
        val models: List<RemoteModel> = emptyList(),
        val modes: List<RemoteMode> = emptyList(),
        /** The answer as the daemon wrote it, for the feature clients that read their own fields. */
        val raw: JsonObject? = null,
    ) : PaseoFrame

    /** Something the daemon said unasked that the session itself does not use (terminal output, …). */
    data class Push(
        val type: String,
        val payload: JsonObject,
    ) : PaseoFrame

    /** A frame this app has no use for. */
    data object Other : PaseoFrame
}

/**
 * Reads the daemon's frames. Anything unknown or malformed is [PaseoFrame.Other]: a newer daemon
 * must not break the app. Pure.
 */
object PaseoFrames {
    private val json = Json { ignoreUnknownKeys = true }
    private const val FAILED = "电脑那边没有做成"
    private const val REFUSED = "电脑没有接收这条消息"

    fun parse(frame: String): PaseoFrame {
        val root = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull()
        val message = root?.obj("message") ?: root
        val payload = message?.obj("payload")
        if (message == null || payload == null) return PaseoFrame.Other
        val frame = read(message.text("type"), payload)
        return if (frame is PaseoFrame.Reply) frame.copy(raw = payload) else frame
    }

    private fun read(
        type: String,
        payload: JsonObject,
    ): PaseoFrame {
        val requestId = payload.text("requestId")
        val agentId = payload.text("agentId")
        return when (type) {
            "status" -> status(payload)
            "fetch_agents_response" -> PaseoFrame.Reply(requestId, agents = PaseoNodes.agentsIn(payload))
            "agent_update" -> live(PaseoNodes.changed(payload))
            "agent_stream" -> live(PaseoNodes.stream(agentId, payload.obj("event")))
            "agent_permission_request" -> live(PaseoNodes.asked(agentId, payload.obj("request")))
            "agent_permission_resolved" -> live(RemoteEvent.PermissionSettled(agentId, requestId))
            "send_agent_message_response" -> receipt(payload)
            "fetch_agent_timeline_response" -> timeline(payload)
            in PaseoCatalogNodes.ANSWERS -> PaseoCatalogNodes.reply(payload)
            "pong" -> PaseoFrame.Reply(requestId)
            "rpc_error" -> PaseoFrame.Reply(requestId, payload.text("error").ifEmpty { FAILED })
            else -> unasked(type, requestId, payload)
        }
    }

    /** An answer the session has no reader for, or — without a request id — something the daemon said unasked. */
    private fun unasked(
        type: String,
        requestId: String,
        payload: JsonObject,
    ): PaseoFrame =
        if (requestId.isEmpty()) {
            PaseoFrame.Push(type, payload)
        } else {
            PaseoFrame.Reply(requestId, payload.text("error"))
        }

    private fun live(event: RemoteEvent?): PaseoFrame = event?.let { PaseoFrame.Live(it) } ?: PaseoFrame.Other

    private fun timeline(payload: JsonObject): PaseoFrame =
        PaseoFrame.Reply(payload.text("requestId"), payload.text("error"), items = PaseoNodes.itemsIn(payload))

    private fun status(payload: JsonObject): PaseoFrame {
        val requestId = payload.text("requestId")
        return when (payload.text("status")) {
            "server_info" -> serverInfo(payload)
            "agent_created" -> PaseoFrame.Reply(requestId, agent = payload.obj("agent")?.let(PaseoNodes::agent))
            "agent_create_failed" -> PaseoFrame.Reply(requestId, payload.text("error").ifEmpty { FAILED })
            else -> PaseoFrame.Other
        }
    }

    private fun serverInfo(payload: JsonObject): PaseoFrame =
        PaseoFrame.ServerInfo(payload.text("serverId"), payload.text("hostname"), payload.text("version"))

    private fun receipt(payload: JsonObject): PaseoFrame {
        val refused = if (payload.flag("accepted") == true) "" else payload.text("error").ifEmpty { REFUSED }
        return PaseoFrame.Reply(payload.text("requestId"), refused)
    }
}
