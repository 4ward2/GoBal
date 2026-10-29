package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteAgent
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteChoice
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteEvent
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteItem
import com.danielealbano.androidremotecontrolmcp.services.remote.RemotePermission
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.Instant

internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.list(key: String): List<Any?> = (this[key] as? JsonArray).orEmpty()

/** Turns the objects inside the daemon's frames into the app's own types. Pure. */
internal object PaseoNodes {
    private const val DETAIL_CHARS = 600
    private val ENDED = setOf("turn_completed", "turn_failed", "turn_canceled")

    fun agentsIn(payload: JsonObject): List<RemoteAgent> =
        payload.list("entries").mapNotNull { entry -> (entry as? JsonObject)?.obj("agent")?.let(::agent) }

    fun itemsIn(payload: JsonObject): List<RemoteItem> =
        payload.list("entries").mapNotNull { entry -> (entry as? JsonObject)?.obj("item")?.let(::item) }

    fun changed(payload: JsonObject): RemoteEvent? {
        val upserted = payload.obj("agent")?.let(::agent)
        return when {
            upserted != null -> RemoteEvent.AgentChanged(upserted)
            payload.text("kind") == "remove" -> RemoteEvent.AgentRemoved(payload.text("agentId"))
            else -> null
        }
    }

    fun agent(node: JsonObject): RemoteAgent =
        RemoteAgent(
            id = node.text("id"),
            title = node.text("title"),
            provider = node.text("provider"),
            model = node.text("model"),
            cwd = node.text("cwd"),
            status = node.text("status"),
            archived = node.text("archivedAt").isNotEmpty(),
            pending = node.list("pendingPermissions").mapNotNull { (it as? JsonObject)?.let(::permission) },
            updatedAt = millis(node.text("updatedAt")),
            lastError = node.text("lastError"),
            lastUserMessageAt = millis(node.text("lastUserMessageAt")),
        )

    fun stream(
        agentId: String,
        event: JsonObject?,
    ): RemoteEvent? {
        val type = event?.text("type").orEmpty()
        val item = event?.obj("item")?.let(::item)
        return when {
            event == null -> null
            type == "turn_started" -> RemoteEvent.TurnStarted(agentId)
            type in ENDED -> RemoteEvent.TurnEnded(agentId, event.text("error"), cancelled = type == "turn_canceled")
            type == "timeline" && item != null -> RemoteEvent.Item(agentId, item)
            type == "permission_requested" -> asked(agentId, event.obj("request"))
            type == "permission_resolved" -> RemoteEvent.PermissionSettled(agentId, event.text("requestId"))
            else -> null
        }
    }

    fun asked(
        agentId: String,
        request: JsonObject?,
    ): RemoteEvent? = request?.let { RemoteEvent.PermissionAsked(agentId, permission(it)) }

    private fun item(node: JsonObject): RemoteItem? =
        when (node.text("type")) {
            "user_message" -> user(node)
            "assistant_message" -> RemoteItem.Assistant(node.text("text"), node.text("messageId"))
            "reasoning" -> RemoteItem.Reasoning(node.text("text"))
            "tool_call" -> tool(node)
            else -> null
        }

    private fun user(node: JsonObject): RemoteItem.User {
        val id = node.text("messageId").ifEmpty { node.text("clientMessageId") }
        return RemoteItem.User(node.text("text"), id)
    }

    private fun tool(node: JsonObject): RemoteItem.Tool {
        // What the tool does, in one line.
        val summary = said(node.obj("detail")).lineSequence().firstOrNull().orEmpty()
        return RemoteItem.Tool(node.text("callId"), node.text("name"), summary.take(DETAIL_CHARS), node.text("status"))
    }

    /**
     * What is asked, said as fully as a card can hold: the request's own description, else what
     * the tool would do, else the plan or command in its input, else the input as it is.
     */
    private fun permission(node: JsonObject): RemotePermission {
        val input = node.obj("input")
        val told = node.text("description").ifEmpty { said(node.obj("detail")) }.ifEmpty { said(input) }
        val detail = told.ifEmpty { input?.toString().orEmpty() }.trim()
        val choices = node.list("actions").mapNotNull { (it as? JsonObject)?.let(::choice) }
        return RemotePermission(
            node.text("id"),
            node.text("name"),
            node.text("title"),
            detail.take(DETAIL_CHARS),
            choices,
        )
    }
}

private val SUMMARY_FIELDS =
    listOf("command", "plan", "filePath", "file_path", "path", "query", "url", "pattern", "description", "title")

/** Whichever of the fields that say what is being done is there. */
private fun said(node: JsonObject?): String {
    val found = SUMMARY_FIELDS.firstNotNullOfOrNull { key -> node?.text(key)?.takeIf { it.isNotEmpty() } }
    return found.orEmpty()
}

/** One of the agent's own answers (AgentPermissionActionSchema); one without a label cannot be shown. */
private fun choice(node: JsonObject): RemoteChoice? {
    val behavior = node.text("behavior")
    return RemoteChoice(node.text("id"), node.text("label"), behavior == "allow").takeIf {
        it.id.isNotEmpty() && it.label.isNotEmpty() && (behavior == "allow" || behavior == "deny")
    }
}

/** An ISO time from the daemon in epoch milliseconds; 0 when absent or unreadable. */
private fun millis(iso: String): Long = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0)
