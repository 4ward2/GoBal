package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.Serializable

/** Wire format a provider speaks: OpenAI's `/chat/completions` or Anthropic's `/v1/messages`. */
@Serializable
enum class ProviderKind {
    OPENAI,
    ANTHROPIC,
}

/**
 * A language-model service the user configured under 设置 → 服务商. Each contact picks one of its
 * [models] (or the on-device default) as its conversation model.
 */
@Serializable
data class ModelProvider(
    val id: String,
    val name: String,
    val kind: ProviderKind = ProviderKind.OPENAI,
    val baseUrl: String,
    val apiKey: String = "",
    val models: List<String> = emptyList(),
    /** Context window in tokens for this provider's models; 0 = guess from the model name. */
    val contextWindow: Int = 0,
    /** Each model's context window as the service reported it when its models were fetched. */
    val modelContexts: Map<String, Int> = emptyMap(),
)

/** Which provider and model a contact talks through; blank = the phone's `provider.json` default. */
@Serializable
data class ModelChoice(
    val providerId: String = "",
    val model: String = "",
) {
    val onDevice: Boolean get() = providerId.isBlank()

    companion object {
        val ON_DEVICE = ModelChoice()
    }
}

/** What the plain assistant says about itself; the counterpart of a character card's text. */
@Serializable
data class AssistantProfile(
    val description: String = "",
    val backstory: String = "",
)
