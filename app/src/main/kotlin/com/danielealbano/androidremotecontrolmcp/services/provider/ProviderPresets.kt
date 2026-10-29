package com.danielealbano.androidremotecontrolmcp.services.provider

import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import java.net.URI

/**
 * A well-known model service: enough to set it up from a name and a key alone.
 *
 * @param aliases brand names that identify it on their own ("deepseek", "通义千问"), matched with
 *   spaces removed so a recogniser's "deep seek" still counts.
 * @param looseAliases words that name it only in context ("小米", "google"): they count only when
 *   the sentence is clearly about model services, so "添加小米的联系人" stays ordinary talk.
 * @param preferredModel picked first when it is in the fetched list.
 * @param keyCheckUrl an endpoint that answers only a valid key, for services whose model list is
 *   public (OpenRouter's answers anyone, so it proves nothing about the key).
 */
data class ProviderPreset(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val aliases: List<String>,
    val looseAliases: List<String> = emptyList(),
    val preferredModel: String = "",
    val keyCheckUrl: String = "",
)

/**
 * The services the assistant can set up by name. Every base URL was checked to answer its
 * model-list endpoint with 401/403 (auth required) rather than 404 or a DNS failure.
 */
object ProviderPresets {
    private val OPENAI = ProviderKind.OPENAI

    val ALL: List<ProviderPreset> =
        listOf(
            ProviderPreset(
                "deepseek",
                "DeepSeek",
                OPENAI,
                "https://api.deepseek.com/v1",
                listOf("deepseek", "深度求索"),
                preferredModel = "deepseek-chat",
            ),
            ProviderPreset(
                "qwen",
                "通义千问",
                OPENAI,
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                listOf("通义千问", "千问", "qwen", "dashscope", "阿里百炼", "百炼"),
                listOf("通义", "阿里云", "阿里"),
                preferredModel = "qwen-plus",
            ),
            ProviderPreset(
                "zhipu",
                "智谱 GLM",
                OPENAI,
                "https://open.bigmodel.cn/api/paas/v4",
                listOf("智谱", "bigmodel", "智谱清言"),
                listOf("glm", "清言"),
                preferredModel = "glm-4-flash",
            ),
            ProviderPreset(
                "kimi",
                "Kimi",
                OPENAI,
                "https://api.moonshot.cn/v1",
                listOf("kimi", "moonshot", "月之暗面"),
                preferredModel = "moonshot-v1-8k",
            ),
            ProviderPreset(
                "doubao",
                "豆包（火山方舟）",
                OPENAI,
                "https://ark.cn-beijing.volces.com/api/v3",
                listOf("豆包", "火山方舟", "火山引擎", "doubao", "volcengine"),
                listOf("方舟", "ark"),
            ),
            ProviderPreset(
                "siliconflow",
                "硅基流动",
                OPENAI,
                "https://api.siliconflow.cn/v1",
                listOf("硅基流动", "siliconflow"),
                listOf("硅基"),
            ),
            ProviderPreset("mimo", "小米 MiMo", OPENAI, "https://api.xiaomimimo.com/v1", listOf("mimo"), listOf("小米")),
            ProviderPreset(
                "hunyuan",
                "腾讯混元",
                OPENAI,
                "https://api.hunyuan.cloud.tencent.com/v1",
                listOf("混元", "hunyuan"),
                listOf("腾讯"),
            ),
            ProviderPreset("minimax", "MiniMax", OPENAI, "https://api.minimaxi.com/v1", listOf("minimax"), listOf("海螺")),
            ProviderPreset(
                "qianfan",
                "百度千帆",
                OPENAI,
                "https://qianfan.baidubce.com/v2",
                listOf("千帆", "文心一言", "文心", "ernie", "qianfan"),
                listOf("百度"),
            ),
            ProviderPreset("stepfun", "阶跃星辰", OPENAI, "https://api.stepfun.com/v1", listOf("阶跃星辰", "阶跃", "stepfun")),
            ProviderPreset("baichuan", "百川", OPENAI, "https://api.baichuan-ai.com/v1", listOf("百川", "baichuan")),
            ProviderPreset(
                "openai",
                "OpenAI",
                OPENAI,
                "https://api.openai.com/v1",
                listOf("openai", "chatgpt"),
                listOf("gpt"),
                preferredModel = "gpt-4o-mini",
            ),
            ProviderPreset(
                "anthropic",
                "Claude（Anthropic）",
                ProviderKind.ANTHROPIC,
                "https://api.anthropic.com",
                listOf("claude", "anthropic"),
            ),
            ProviderPreset(
                "gemini",
                "Gemini",
                OPENAI,
                "https://generativelanguage.googleapis.com/v1beta/openai",
                listOf("gemini"),
                listOf("谷歌", "google"),
            ),
            ProviderPreset(
                "commandcode",
                "Command Code",
                OPENAI,
                "https://api.commandcode.ai/provider/v1",
                listOf("commandcode", "cmdcode"),
                preferredModel = "deepseek/deepseek-v4-flash",
                // Its model list answers without a key; whoami answers only a valid one.
                keyCheckUrl = "https://api.commandcode.ai/alpha/whoami",
            ),
            ProviderPreset(
                "openrouter",
                "OpenRouter",
                OPENAI,
                "https://openrouter.ai/api/v1",
                listOf("openrouter"),
                keyCheckUrl = "https://openrouter.ai/api/v1/key",
            ),
            ProviderPreset("groq", "Groq", OPENAI, "https://api.groq.com/openai/v1", listOf("groq")),
            ProviderPreset("xai", "Grok（xAI）", OPENAI, "https://api.x.ai/v1", listOf("grok", "xai")),
            ProviderPreset("mistral", "Mistral", OPENAI, "https://api.mistral.ai/v1", listOf("mistral")),
        )

    fun byId(id: String?): ProviderPreset? = ALL.firstOrNull { it.id == id }

    /**
     * The preset a sentence names. [compact] is the lower-cased text without spaces; the longest
     * alias wins so "通义千问" is not read as a shorter alias inside it.
     */
    fun named(
        compact: String,
        allowLoose: Boolean,
    ): ProviderPreset? =
        ALL
            .flatMap { preset ->
                val words = if (allowLoose) preset.aliases + preset.looseAliases else preset.aliases
                words.map { it to preset }
            }.filter { (alias, _) -> compact.contains(alias) }
            .maxByOrNull { (alias, _) -> alias.length }
            ?.second

    /** The preset whose address is on the same host as [url], if any. */
    fun byHost(url: String): ProviderPreset? {
        val host = hostOf(url) ?: return null
        return ALL.firstOrNull { hostOf(it.baseUrl) == host }
    }

    fun hostOf(url: String): String? =
        runCatching { URI(url.trim()).host?.lowercase() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
}
