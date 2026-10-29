package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** An image sent to a vision model, already base64-encoded. */
data class ImageData(
    val mime: String,
    val base64: String,
)

/** One turn as a provider sees it: text plus any images (only vision models look at them). */
data class ApiMessage(
    val role: String,
    val text: String,
    val images: List<ImageData> = emptyList(),
)

/** Where and how to call the two provider wire formats; pure, so it is unit-tested without a network. */
object ProviderRequests {
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val OPENAI_CHAT = "chat/completions"
    private const val OPENAI_MODELS = "models"
    private const val ANTHROPIC_MESSAGES = "messages"
    private const val ANTHROPIC_MODELS = "models"
    private const val V1 = "v1"

    fun chatUrl(
        kind: ProviderKind,
        baseUrl: String,
    ): String =
        when (kind) {
            ProviderKind.OPENAI -> join(baseUrl, OPENAI_CHAT)
            ProviderKind.ANTHROPIC -> join(withV1(baseUrl), ANTHROPIC_MESSAGES)
        }

    fun modelsUrl(
        kind: ProviderKind,
        baseUrl: String,
    ): String =
        when (kind) {
            ProviderKind.OPENAI -> join(baseUrl, OPENAI_MODELS)
            ProviderKind.ANTHROPIC -> join(withV1(baseUrl), ANTHROPIC_MODELS)
        }

    /** Request headers for [kind]; the key goes where each API expects it. */
    fun headers(
        kind: ProviderKind,
        apiKey: String,
    ): Map<String, String> =
        when (kind) {
            ProviderKind.OPENAI -> mapOf("Authorization" to "Bearer $apiKey")
            ProviderKind.ANTHROPIC -> mapOf("x-api-key" to apiKey, "anthropic-version" to ANTHROPIC_VERSION)
        }

    fun body(
        kind: ProviderKind,
        model: String,
        messages: List<ApiMessage>,
    ): String =
        when (kind) {
            ProviderKind.OPENAI -> ProviderBodies.openAi(model, messages)
            ProviderKind.ANTHROPIC -> ProviderBodies.anthropic(model, messages)
        }

    fun parseReply(
        kind: ProviderKind,
        body: String,
    ): ChatApiResult =
        when (kind) {
            ProviderKind.OPENAI -> ProviderReplies.openAi(body)
            ProviderKind.ANTHROPIC -> ProviderReplies.anthropic(body)
        }

    private fun join(
        base: String,
        path: String,
    ): String = base.trimEnd('/') + "/" + path

    /** Anthropic bases are given with or without `/v1`; both end up at `…/v1/messages`. */
    private fun withV1(base: String): String {
        val trimmed = base.trimEnd('/')
        return if (trimmed.endsWith("/$V1")) trimmed else "$trimmed/$V1"
    }
}

/** Request bodies for the two wire formats. */
object ProviderBodies {
    private const val ROLE_SYSTEM = "system"
    private const val ANTHROPIC_MAX_TOKENS = 4096

    fun openAi(
        model: String,
        messages: List<ApiMessage>,
    ): String =
        buildJsonObject {
            put("model", model)
            put("messages", turns(messages, ::openAiParts))
        }.toString()

    fun anthropic(
        model: String,
        messages: List<ApiMessage>,
    ): String {
        val system = messages.filter { it.role == ROLE_SYSTEM }.joinToString("\n\n") { it.text }
        return buildJsonObject {
            put("model", model)
            put("max_tokens", ANTHROPIC_MAX_TOKENS)
            if (system.isNotBlank()) put("system", system)
            put("messages", turns(messages.filter { it.role != ROLE_SYSTEM }, ::anthropicParts))
        }.toString()
    }

    /** Text-only turns stay plain strings; turns with images become part lists. */
    private fun turns(
        messages: List<ApiMessage>,
        parts: (ApiMessage) -> JsonArray,
    ): JsonArray =
        buildJsonArray {
            messages.forEach { m ->
                add(
                    buildJsonObject {
                        put("role", m.role)
                        if (m.images.isEmpty()) put("content", m.text) else put("content", parts(m))
                    },
                )
            }
        }

    private fun openAiParts(m: ApiMessage): JsonArray =
        buildJsonArray {
            if (m.text.isNotBlank()) add(textPart(m.text))
            m.images.forEach { image ->
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", "data:${image.mime};base64,${image.base64}") })
                    },
                )
            }
        }

    private fun anthropicParts(m: ApiMessage): JsonArray =
        buildJsonArray {
            m.images.forEach { image ->
                add(
                    buildJsonObject {
                        put("type", "image")
                        put(
                            "source",
                            buildJsonObject {
                                put("type", "base64")
                                put("media_type", image.mime)
                                put("data", image.base64)
                            },
                        )
                    },
                )
            }
            if (m.text.isNotBlank()) add(textPart(m.text))
        }

    private fun textPart(text: String): JsonObject =
        buildJsonObject {
            put("type", "text")
            put("text", text)
        }
}

/** Reply parsing for the two wire formats, plus the shared `GET …/models` listing. */
object ProviderReplies {
    private const val ERROR_PREVIEW = 300
    private val json = Json { ignoreUnknownKeys = true }

    fun openAi(body: String): ChatApiResult {
        val root = parseObject(body) ?: return ChatApiResult(ok = false, error = body.take(ERROR_PREVIEW))
        val content =
            (root["choices"] as? JsonArray)
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.let { textOf(it) }
        return content?.takeIf { it.isNotBlank() }?.let { ChatApiResult(ok = true, content = it) }
            ?: ChatApiResult(ok = false, error = errorText(root, body))
    }

    fun anthropic(body: String): ChatApiResult {
        val root = parseObject(body) ?: return ChatApiResult(ok = false, error = body.take(ERROR_PREVIEW))
        val content =
            (root["content"] as? JsonArray)
                ?.mapNotNull { part ->
                    val obj = part as? JsonObject
                    val isText = obj?.get("type")?.jsonPrimitive?.contentOrNull == "text"
                    obj?.takeIf { isText }?.get("text")?.let(::textOf)
                }?.joinToString("")
        return content?.takeIf { it.isNotBlank() }?.let { ChatApiResult(ok = true, content = it) }
            ?: ChatApiResult(ok = false, error = errorText(root, body))
    }

    /** OpenAI-style content is a string, or a list of `{type:"text",text:…}` parts. */
    private fun textOf(element: JsonElement): String =
        when (element) {
            is JsonPrimitive -> {
                element.contentOrNull.orEmpty()
            }

            is JsonArray -> {
                val texts = element.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
                texts.joinToString("")
            }

            else -> {
                ""
            }
        }

    private fun errorText(
        root: JsonObject,
        body: String,
    ): String {
        val error = root["error"]
        val message =
            when (error) {
                is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> error.contentOrNull
                else -> null
            }
        return message?.takeIf { it.isNotBlank() } ?: body.take(ERROR_PREVIEW)
    }

    private fun parseObject(raw: String): JsonObject? {
        val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }
        return parsed.getOrNull()
    }
}
