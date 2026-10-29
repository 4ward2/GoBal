package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Data class for agent admin operations. */
data class AgentDetails(
    val id: String,
    val title: String,
    val provider: String,
    val model: String?,
    val modeId: String?,
    val thinkingOptionId: String?,
    val status: String,
    val archived: Boolean,
    val cwd: String,
    val labels: Map<String, String>,
    val persistence: JsonObject? = null,
)

/** Requests for agent administration (lines 912-1923 in messages.ts). Pure. */
object PaseoAgentAdminRequests {
    fun fetchAgent(
        requestId: String,
        agentId: String,
    ): String =
        buildJsonObject {
            put("type", "session")
            putJsonObject("message") {
                put("type", "fetch_agent_request")
                put("requestId", requestId)
                put("agentId", agentId)
            }
        }.toString()

    fun updateAgent(
        requestId: String,
        agentId: String,
        name: String? = null,
        labels: Map<String, String>? = null,
    ): String =
        buildJsonObject {
            put("type", "session")
            putJsonObject("message") {
                put("type", "update_agent_request")
                put("requestId", requestId)
                put("agentId", agentId)
                if (name != null) put("name", name)
                if (labels != null) {
                    putJsonObject("labels") {
                        labels.forEach { (k, v) -> put(k, v) }
                    }
                }
            }
        }.toString()

    fun archiveAgent(
        requestId: String,
        agentId: String,
    ): String =
        buildJsonObject {
            put("type", "session")
            putJsonObject("message") {
                put("type", "archive_agent_request")
                put("requestId", requestId)
                put("agentId", agentId)
            }
        }.toString()

    fun deleteAgent(
        requestId: String,
        agentId: String,
    ): String =
        buildJsonObject {
            put("type", "session")
            putJsonObject("message") {
                put("type", "delete_agent_request")
                put("requestId", requestId)
                put("agentId", agentId)
            }
        }.toString()

    fun setMode(
        requestId: String,
        agentId: String,
        modeId: String,
    ): String =
        buildJsonObject {
            put("type", "session")
            putJsonObject("message") {
                put("type", "set_agent_mode_request")
                put("requestId", requestId)
                put("agentId", agentId)
                put("modeId", modeId)
            }
        }.toString()

    fun setModel(
        requestId: String,
        agentId: String,
        modelId: String?,
    ): String =
        buildJsonObject {
            put("type", "session")
            putJsonObject("message") {
                put("type", "set_agent_model_request")
                put("requestId", requestId)
                put("agentId", agentId)
                put("modelId", modelId)
            }
        }.toString()

    fun setThinking(
        requestId: String,
        agentId: String,
        thinkingOptionId: String?,
    ): String =
        buildJsonObject {
            put("type", "session")
            putJsonObject("message") {
                put("type", "set_agent_thinking_request")
                put("requestId", requestId)
                put("agentId", agentId)
                put("thinkingOptionId", thinkingOptionId)
            }
        }.toString()

    fun refreshAgent(
        requestId: String,
        agentId: String,
    ): String =
        buildJsonObject {
            put("type", "session")
            putJsonObject("message") {
                put("type", "refresh_agent_request")
                put("requestId", requestId)
                put("agentId", agentId)
            }
        }.toString()

    fun resumeAgent(
        requestId: String,
        handle: JsonObject,
        overrides: JsonObject? = null,
    ): String =
        buildJsonObject {
            put("type", "session")
            putJsonObject("message") {
                put("type", "resume_agent_request")
                put("requestId", requestId)
                put("handle", handle)
                if (overrides != null) put("overrides", overrides)
            }
        }.toString()
}

/** Reads admin operation responses (lines 912-1927 in messages.ts). Pure. */
internal object PaseoAgentAdminNodes {
    fun details(payload: JsonObject): AgentDetails {
        val agent = payload.obj("agent") ?: JsonObject(emptyMap())
        return AgentDetails(
            id = agent.text("id"),
            title = agent.text("title"),
            provider = agent.text("provider"),
            model = agent.text("model").ifEmpty { null },
            modeId = agent.text("currentModeId").ifEmpty { null },
            thinkingOptionId = agent.text("thinkingOptionId").ifEmpty { null },
            status = agent.text("status"),
            archived = agent.text("archivedAt").isNotEmpty(),
            cwd = agent.text("cwd"),
            labels =
                agent.obj("labels").orEmpty().mapValues { (_, value) ->
                    (value as? JsonPrimitive)?.contentOrNull.orEmpty()
                },
            persistence = agent.obj("persistence"),
        )
    }

    fun successOrError(payload: JsonObject): Result<Unit> {
        val error = payload.text("error")
        val accepted = payload.flag("accepted")
        return if (error.isNotEmpty()) {
            Result.failure(IllegalStateException(error))
        } else if (accepted == true) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Server rejected the request"))
        }
    }
}

/** Agent admin client for Paseo daemon. */
class PaseoAgentAdmin(
    private val rpc: PaseoRpc,
) {
    suspend fun details(agentId: String): Result<AgentDetails> {
        val reply = rpc.call("fetch_agent_request", buildJsonObject { put("agentId", agentId) })
        return reply.map { PaseoAgentAdminNodes.details(it) }
    }

    suspend fun rename(
        agentId: String,
        title: String,
    ): Result<Unit> {
        val reply =
            rpc.call(
                "update_agent_request",
                buildJsonObject {
                    put("agentId", agentId)
                    put("name", title)
                },
            )
        return reply.mapCatching { PaseoAgentAdminNodes.successOrError(it).getOrThrow() }
    }

    suspend fun archive(agentId: String): Result<Unit> {
        val reply = rpc.call("archive_agent_request", buildJsonObject { put("agentId", agentId) })
        return reply.mapCatching { PaseoAgentAdminNodes.successOrError(it).getOrThrow() }
    }

    suspend fun delete(agentId: String): Result<Unit> {
        val reply = rpc.call("delete_agent_request", buildJsonObject { put("agentId", agentId) })
        return reply.mapCatching { PaseoAgentAdminNodes.successOrError(it).getOrThrow() }
    }

    suspend fun setMode(
        agentId: String,
        modeId: String,
    ): Result<Unit> {
        val reply =
            rpc.call(
                "set_agent_mode_request",
                buildJsonObject {
                    put("agentId", agentId)
                    put("modeId", modeId)
                },
            )
        return reply.mapCatching { PaseoAgentAdminNodes.successOrError(it).getOrThrow() }
    }

    suspend fun setModel(
        agentId: String,
        modelId: String?,
    ): Result<Unit> {
        val reply =
            rpc.call(
                "set_agent_model_request",
                buildJsonObject {
                    put("agentId", agentId)
                    put("modelId", modelId)
                },
            )
        return reply.mapCatching { PaseoAgentAdminNodes.successOrError(it).getOrThrow() }
    }

    suspend fun setThinking(
        agentId: String,
        thinkingOptionId: String?,
    ): Result<Unit> {
        val reply =
            rpc.call(
                "set_agent_thinking_request",
                buildJsonObject {
                    put("agentId", agentId)
                    put("thinkingOptionId", thinkingOptionId)
                },
            )
        return reply.mapCatching { PaseoAgentAdminNodes.successOrError(it).getOrThrow() }
    }

    suspend fun refresh(agentId: String): Result<Unit> {
        val reply = rpc.call("refresh_agent_request", buildJsonObject { put("agentId", agentId) })
        return reply.mapCatching { PaseoAgentAdminNodes.successOrError(it).getOrThrow() }
    }

    suspend fun resume(
        handle: JsonObject?,
        overrides: JsonObject? = null,
    ): Result<Unit> {
        if (handle == null) {
            return Result.failure(IllegalStateException("这个代理没有可恢复的记录"))
        }
        val args =
            buildJsonObject {
                put("handle", handle)
                if (overrides != null) put("overrides", overrides)
            }
        val reply = rpc.call("resume_agent_request", args)
        return reply.mapCatching { PaseoAgentAdminNodes.successOrError(it).getOrThrow() }
    }
}
