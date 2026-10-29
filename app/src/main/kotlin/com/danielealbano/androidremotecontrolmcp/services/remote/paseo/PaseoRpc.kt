package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import com.danielealbano.androidremotecontrolmcp.services.remote.SecureChannel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * The raw Paseo conversation with one computer, for the feature clients built on top of a session
 * (terminals, workspaces, files, agent settings…). Each feature lives in a file of its own and
 * knows its own message types; the session only carries them.
 *
 * Every request is wrapped as `{type:"session", message:{type, requestId, …fields}}`; the answer
 * is the `payload` of the daemon's reply with the same `requestId`.
 */
interface PaseoRpc {
    /**
     * Asks the daemon [type] with [fields] and gives the payload of its answer. Fails when the
     * computer is not connected, does not answer in time, or answers with an error.
     */
    suspend fun call(
        type: String,
        fields: JsonObject = JsonObject(emptyMap()),
    ): Result<JsonObject>

    /** Says [type] without waiting for an answer (typing into a terminal, unsubscribing); false when not sent. */
    fun tell(
        type: String,
        fields: JsonObject = JsonObject(emptyMap()),
    ): Boolean

    /** What the daemon says unasked that the session itself has no use for: terminal output, workspace changes… */
    val pushes: SharedFlow<PaseoFrame.Push>
}

/** The wire form of any request: `{type:"session", message:{type, requestId, …fields}}`. */
internal object PaseoEnvelope {
    fun request(
        type: String,
        requestId: String,
        fields: JsonObject,
    ): String =
        buildJsonObject {
            put("type", "session")
            putJsonObject("message") {
                put("type", type)
                put("requestId", requestId)
                fields.forEach { (key, value) -> if (key != "type" && key != "requestId") put(key, value) }
            }
        }.toString()
}

/** The raw conversation over a session's own inbox and channel; [PaseoSession.rpc] hands it out. */
internal class PaseoLink(
    private val inbox: PaseoInbox,
    private val channel: SecureChannel,
) : PaseoRpc {
    override val pushes: SharedFlow<PaseoFrame.Push> = inbox.pushes

    override suspend fun call(
        type: String,
        fields: JsonObject,
    ): Result<JsonObject> =
        inbox.ask { PaseoEnvelope.request(type, it, fields) }.mapCatching { reply ->
            checkNotNull(reply.raw) { PaseoSession.NO_ANSWER }
        }

    override fun tell(
        type: String,
        fields: JsonObject,
    ): Boolean = inbox.greeted && channel.send(PaseoEnvelope.request(type, UUID.randomUUID().toString(), fields))
}
