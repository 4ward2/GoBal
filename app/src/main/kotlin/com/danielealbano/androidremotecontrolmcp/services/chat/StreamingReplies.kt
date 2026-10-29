package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pulls the text delta out of one server-sent-events line of a streamed chat reply. */
object StreamingReplies {
    private const val DATA_PREFIX = "data:"
    private const val DONE = "[DONE]"
    private val json = Json { ignoreUnknownKeys = true }

    /** The text carried by an SSE line, "" for lines that carry none, null when the stream is over. */
    fun delta(
        kind: ProviderKind,
        line: String,
    ): String? {
        val trimmed = line.trim()
        val isData = trimmed.startsWith(DATA_PREFIX)
        val payload = trimmed.removePrefix(DATA_PREFIX).trim()
        val obj =
            payload
                .takeIf { isData && it != DONE }
                ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        return when {
            isData && payload == DONE -> null
            obj == null -> ""
            kind == ProviderKind.OPENAI -> openAiDelta(obj)
            else -> anthropicDelta(obj)
        }
    }

    private fun openAiDelta(obj: JsonObject): String =
        (obj["choices"] as? JsonArray)
            ?.firstOrNull()
            ?.jsonObject
            ?.get("delta")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.contentOrNull
            .orEmpty()

    /** Anthropic streams `content_block_delta` events; `message_stop` ends the reply. */
    private fun anthropicDelta(obj: JsonObject): String? {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        val delta = obj["delta"]?.jsonObject
        val isText = delta?.get("type")?.jsonPrimitive?.contentOrNull == "text_delta"
        return when {
            type == "message_stop" -> {
                null
            }

            type == "content_block_delta" && isText -> {
                delta
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
            }

            else -> {
                ""
            }
        }
    }

    /**
     * The model's thinking carried by an SSE line, "" when the line carries none. Reasoning models
     * send it apart from the answer: OpenAI-compatible services as `delta.reasoning_content` (or
     * `delta.reasoning`), Anthropic as `thinking_delta` events.
     */
    fun reasoning(
        kind: ProviderKind,
        line: String,
    ): String {
        val payload = line.trim().removePrefix(DATA_PREFIX).trim()
        val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
        val delta =
            if (kind == ProviderKind.OPENAI) {
                (obj?.get("choices") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.get("delta") }
            } else {
                obj?.get("delta")
            }
        val fields = delta as? JsonObject
        val keys = if (kind == ProviderKind.OPENAI) OPENAI_THINKING else ANTHROPIC_THINKING
        return keys.firstNotNullOfOrNull { key -> (fields?.get(key) as? JsonPrimitive)?.contentOrNull }.orEmpty()
    }

    private val OPENAI_THINKING = listOf("reasoning_content", "reasoning")
    private val ANTHROPIC_THINKING = listOf("thinking")

    /** The request body with streaming switched on. */
    fun streamingBody(body: String): String = body.replaceFirst("{", "{\"stream\":true,")
}
