package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * One model a service offers, with its context window when the service states it.
 *
 * @param endpoints the routes that serve it (`/chat/completions`, `/messages`, …) when the service
 *   lists them; empty when it does not say.
 */
data class ModelInfo(
    val id: String,
    val contextWindow: Int? = null,
    val endpoints: List<String> = emptyList(),
)

/** The context windows the service stated, by model id; models it said nothing about are absent. */
fun List<ModelInfo>.reportedContexts(): Map<String, Int> {
    val stated = mapNotNull { model -> model.contextWindow?.let { model.id to it } }
    return stated.toMap()
}

/**
 * Reads a `GET …/models` listing. Services that state a model's context window use different
 * field names (OpenRouter `context_length`, Groq `context_window`, Mistral `max_context_length`,
 * vLLM `max_model_len`, …); all are read, and a model without one is left for [ContextBudget]
 * to estimate from its name.
 */
object ModelCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    private val CONTEXT_FIELDS =
        listOf(
            "context_length",
            "context_window",
            "max_context_length",
            "max_model_len",
            "max_input_tokens",
            "input_token_limit",
            "inputTokenLimit",
            "max_context_tokens",
        )

    /** Nested places some services put it: OpenRouter's `top_provider`, Together's `config`. */
    private val NESTED = listOf("top_provider", "config", "limits")

    /**
     * Markers of models that cannot hold a text conversation: speech, embeddings, ranking,
     * moderation, image generation. Offering them for a chat would only produce errors.
     */
    private val NOT_FOR_CHAT =
        listOf(
            "tts",
            "asr",
            "whisper",
            "embed",
            "rerank",
            "moderation",
            "dall-e",
            "speech",
            "transcri",
            "voice",
            "realtime",
            "-audio",
            "audio-",
            "-image",
            "image-",
            "imagen",
            "stable-diffusion",
            "flux",
        )

    /** Every model in the listing, sorted by id. Both `{"data":[…]}` and `{"models":[…]}` are read. */
    fun parse(body: String): List<ModelInfo> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val entries = (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: JsonArray(emptyList())
        return entries
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj -> idOf(obj)?.let { ModelInfo(it, contextOf(obj), endpointsOf(obj)) } }
            .distinctBy { it.id }
            .sortedBy { it.id }
    }

    /**
     * The models fit for a conversation through [kind]'s wire format: speech, embedding and image
     * models are dropped, and so are models the service says it serves only on another route
     * (Command Code serves Claude only on `/messages`). The whole list is kept when the filter
     * would leave nothing.
     */
    fun chatOnly(
        models: List<ModelInfo>,
        kind: ProviderKind = ProviderKind.OPENAI,
    ): List<ModelInfo> {
        val route = if (kind == ProviderKind.ANTHROPIC) MESSAGES else CHAT_COMPLETIONS
        return models
            .filterNot { model -> NOT_FOR_CHAT.any { model.id.lowercase().contains(it) } }
            .filter { model -> model.endpoints.isEmpty() || model.endpoints.any { it.endsWith(route) } }
            .ifEmpty { models }
    }

    private const val CHAT_COMPLETIONS = "/chat/completions"
    private const val MESSAGES = "/messages"

    private fun endpointsOf(obj: JsonObject): List<String> =
        (obj["supported_endpoints"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun idOf(obj: JsonObject): String? =
        (obj["id"] ?: obj["name"])
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.removePrefix("models/")
            ?.takeIf { it.isNotBlank() }

    private fun contextOf(obj: JsonObject): Int? {
        val direct = CONTEXT_FIELDS.firstNotNullOfOrNull { positive(obj[it]) }
        val nested =
            NESTED.firstNotNullOfOrNull { key ->
                (obj[key] as? JsonObject)?.let { inner -> CONTEXT_FIELDS.firstNotNullOfOrNull { positive(inner[it]) } }
            }
        return direct ?: nested
    }

    private fun positive(element: JsonElement?): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        val value = primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
        return value?.takeIf { it >= 1 && it <= Int.MAX_VALUE }?.toInt()
    }
}
