package com.danielealbano.androidremotecontrolmcp.services.provider

import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import com.danielealbano.androidremotecontrolmcp.services.provider.web.ApiUrls

/**
 * What one sentence says about setting up a model service. Any field may be blank: the
 * confirmation card fills in the rest (pick the service, paste the key).
 */
data class ProviderDraft(
    val presetId: String? = null,
    val name: String = "",
    val kind: ProviderKind = ProviderKind.OPENAI,
    val baseUrl: String = "",
    val apiKey: String = "",
    /** A link that is not an API address (a documentation page): read it to find one. */
    val sourceUrl: String = "",
    /** The model a pasted example or the documentation asks for; preferred when the service has it. */
    val model: String = "",
)

/**
 * Fixed rules, no language model: "添加服务商 DeepSeek", "接入通义千问", "DeepSeek 的密钥是 sk-…",
 * a pasted key on its own, "添加服务商 https://…/v1 sk-…" for a service with no preset, a pasted
 * curl example (its endpoint URL, key and model are all taken), or a documentation link, which the
 * card then reads to find the API address.
 *
 * A sentence holding anything that looks like an API key is always taken, whatever else it says,
 * so a key pasted into the chat by mistake never reaches a model or the chat history.
 */
object ProviderIntentParser {
    private val ADD_VERBS =
        listOf(
            "添加",
            "加个",
            "加一个",
            "加上",
            "新增",
            "接入",
            "配置",
            "绑定",
            "填入",
            "填上",
            "换成",
            "改成",
            "更新",
            "add",
            "connect",
            "setup",
            "configure",
        )

    /** Words that make a sentence about model services. Matched on the space-less text. */
    private val TOPIC_WORDS = listOf("服务商", "密钥", "秘钥", "apikey", "api密钥")

    /** English topic words as whole words, so "rapid" or "keyboard" do not count. */
    private val TOPIC_REGEX = Regex("(?i)\\b(?:api|key|provider)\\b")

    private val URL = Regex("https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]+")
    private val MODEL = Regex("[\"']model[\"']\\s*:\\s*[\"']([A-Za-z0-9._:/-]{2,80})[\"']")
    private val SPACES = Regex("\\s+")

    fun parse(text: String): ProviderDraft? {
        val compact = SPACES.replace(text.lowercase(), "")
        val onTopic = TOPIC_WORDS.any { compact.contains(it) } || TOPIC_REGEX.containsMatchIn(text)
        val key = ApiKeys.find(text, aboutKeys = onTopic)
        val url = URL.find(text)?.value?.trimEnd('/', '.', ',', ')', '\'', '"')
        val named = ProviderPresets.named(compact, allowLoose = onTopic)
        val asked = ADD_VERBS.any { compact.contains(it) } && (named != null || onTopic)
        val wanted = key != null || asked || (url != null && onTopic)
        val model =
            MODEL
                .find(text)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        return if (wanted) draftOf(named, key, url).copy(model = model) else null
    }

    private fun draftOf(
        named: ProviderPreset?,
        key: FoundKey?,
        url: String?,
    ): ProviderDraft {
        val preset = named ?: ProviderPresets.byId(key?.presetId) ?: url?.let { ProviderPresets.byHost(it) }
        // A link is either an API address (maybe with an endpoint on it) or a page to read.
        val base = url?.let { ApiUrls.baseOf(it) }
        val docs = if (preset == null && url != null && base == null) url else ""
        val address = if (preset != null) preset.baseUrl else base.orEmpty()
        val throughMessages = url?.let { ApiUrls.endpointOf(it) } == "/messages" || address.contains("anthropic")
        val kind = preset?.kind ?: if (throughMessages) ProviderKind.ANTHROPIC else ProviderKind.OPENAI
        return ProviderDraft(
            presetId = preset?.id,
            name = preset?.name ?: base?.let { nameFromHost(it) }.orEmpty(),
            kind = kind,
            baseUrl = address,
            apiKey = key?.key.orEmpty(),
            sourceUrl = docs,
        )
    }

    /** "https://api.example.com/v1" → "example": a starting point the card lets the user change. */
    private fun nameFromHost(url: String): String {
        val host = ProviderPresets.hostOf(url) ?: return ""
        return host
            .split('.')
            .firstOrNull { it.isNotBlank() && it != "api" && it != "www" }
            .orEmpty()
    }
}
