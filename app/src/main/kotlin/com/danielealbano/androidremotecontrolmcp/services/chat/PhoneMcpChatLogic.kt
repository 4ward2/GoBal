package com.danielealbano.androidremotecontrolmcp.services.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pure chat/STT helpers used by the native Chat tab (no WebView / LongCat). */
object PhoneMcpChatLogic {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val secretKeys =
        setOf("api_key", "apikey", "token", "password", "secret", "auth_token", "authorization")

    private val COMPUTER_WORDS = listOf("电脑", "配对", "口令", "加密传输")

    fun formatChatError(raw: String): String {
        val t = raw.trim()
        val lower = t.lowercase()
        // What went wrong with a paired computer is already said in plain words.
        if (COMPUTER_WORDS.any { it in t }) return t
        return when {
            // A provider from 设置 → 服务商 answered with an HTTP error: show it as is, it is not provider.json.
            t.startsWith("HTTP ") -> "服务商出错：${t.take(PROVIDER_ERROR_PREVIEW)}"
            "503" in t || "10110" in t || "overload" in lower || "繁忙" in t || "engine busy" in lower ->
                "模型服务繁忙，请稍后重试"
            "wake su timeout" in lower || ("wake" in lower && "timeout" in lower) ->
                "启动机上操控超时：请检查 Magisk 是否授权本 App root，并确认 NetHunter chroot 可用"
            "timeout" in lower || "超时" in t ->
                "对话超时，请稍后重试"
            "401" in t || "unauthorized" in lower || "api key" in lower || "未配置" in t ->
                "机上 LLM 未配置：请检查 Magisk /data/adb/phonemcp-voice/provider.json"
            "bad request" in lower || "http verb" in lower || "{}post" in lower ->
                "本地对话服务请求异常，请重试"
            t.isBlank() -> "对话失败"
            else -> "对话失败：${t.take(220)}"
        }
    }

    fun newBubble(role: String, content: String): ChatBubble =
        ChatBubble(role = role, content = content, id = java.util.UUID.randomUUID().toString())

    fun canSendDraft(draft: String, sending: Boolean, listening: Boolean): Boolean =
        draft.isNotBlank() && !sending && !listening

    /**
     * Short UI-control commands should go to Magisk [agent_runner] via `/phonemcp/v1/wake`,
     * not the chat-only LLM (which has no MCP tools and would only *claim* to act).
     *
     * Polite lead-ins ("帮我", "请", "麻烦你", "我想"…) are stripped first, so "帮我打开美团" is judged
     * as "打开美团". Questions ("怎么打开…") stay with the chat model.
     */
    fun isPhoneControlIntent(text: String): Boolean {
        val t = text.trim()
        val core = t.replaceFirst(POLITE_PREFIX, "").trimStart('，', ',', ' ')
        val isQuestion = QA_PREFIX.containsMatchIn(t) || QA_PREFIX.containsMatchIn(core)
        val isCommand =
            CONTROL_PREFIX.containsMatchIn(core) ||
                CONTROL_VERB.containsMatchIn(core) ||
                IN_APP_TASK.containsMatchIn(core)
        return t.isNotEmpty() && t.length <= MAX_CONTROL_LENGTH && !isQuestion && isCommand
    }

    fun wakeRequestJson(text: String, source: String = "native-chat"): String =
        """{"text":${quote(text)},"source":${quote(source)}}"""

    fun parseWakeResponse(body: String): WakeResult {
        val obj = parseObject(body) ?: return WakeResult(ok = false, error = body.take(220))
        return if (obj.bool("ok")) {
            WakeResult(
                ok = true,
                wakeId = obj.str("wake_id"),
                message = obj.str("message"),
            )
        } else {
            WakeResult(ok = false, error = obj.str("error").ifBlank { body.take(220) })
        }
    }

    /** What a task's message says in the stored history (the chat shows its card, not this text). */
    const val TASK_ACK = "已经开始在手机上操作，请看手机屏幕。"

    /** The task id of a task handed to the helper process on rooted phones: never shown, only kept. */
    fun wakeTaskId(wakeId: String): String = "wake:${wakeId.ifBlank { "unknown" }}"

    /** The assistant's message for a phone task; [taskId] ties it to the task's progress. */
    fun taskBubble(taskId: String): ChatBubble = newBubble(role = "assistant", content = TASK_ACK).copy(task = taskId)

    fun ensureBubbleIds(messages: List<ChatBubble>): List<ChatBubble> {
        val seen = HashSet<String>()
        return messages.mapIndexed { index, bubble ->
            var id =
                bubble.id.ifBlank {
                    "i$index-${bubble.role}-${bubble.content.hashCode()}-${bubble.content.length}"
                }
            if (!seen.add(id)) {
                id = "$id-$index"
                seen.add(id)
            }
            if (id == bubble.id) bubble else bubble.copy(id = id)
        }
    }

    fun lazyColumnKey(index: Int, bubble: ChatBubble): String {
        val id = bubble.id
        return if (id.isNotBlank()) id else "i$index-${bubble.role}-${bubble.content.hashCode()}-${bubble.content.length}"
    }

    fun chatRequestJson(
        messages: List<Pair<String, String>>,
        timeoutS: Int,
    ): String {
        val items =
            messages.joinToString(",") { (role, content) ->
                """{"role":${quote(role)},"content":${quote(content)}}"""
            }
        return """{"messages":[$items],"timeout_s":$timeoutS}"""
    }

    fun parseChatResponse(body: String): ChatApiResult {
        val obj = parseObject(body) ?: return ChatApiResult(ok = false, error = body.take(220))
        val ok = obj.bool("ok")
        val content = obj.str("content")
        val error = obj.str("error")
        return if (ok && content.isNotBlank()) {
            ChatApiResult(ok = true, content = content)
        } else {
            ChatApiResult(ok = false, error = error.ifBlank { body.take(220) })
        }
    }

    fun summarizeProvider(raw: String): ProviderSummary {
        val obj = parseObject(raw) ?: return ProviderSummary()
        val kind = obj.str("provider").ifBlank { obj.str("kind") }
        val model = obj.str("model")
        val endpoint = obj.str("base_url").ifBlank { obj.str("endpoint") }
        val authEnv = obj.str("auth_env")
        val configured = kind.isNotBlank() || model.isNotBlank()
        return ProviderSummary(
            configured = configured,
            kind = kind,
            model = model,
            endpoint = endpoint,
            authEnv = authEnv,
        )
    }

    fun parseStatus(body: String): LoopbackStatus {
        val obj = parseObject(body) ?: return LoopbackStatus()
        val providerEl = obj["provider"]
        val provider =
            if (providerEl is JsonObject) {
                summarizeProvider(providerEl.toString())
            } else {
                ProviderSummary()
            }
        return LoopbackStatus(
            httpOk = obj.bool("ok"),
            sttListening = obj.bool("stt_listening"),
            agentWatch = obj.bool("agent_watch"),
            agentRunner = obj.bool("agent_runner"),
            provider = provider,
        )
    }

    private val LANG_CODE = Regex("[a-z]{2}(-[a-z]{2})?")

    /** @param lang recognizer language ("zh", "en", "en-in", "hi"); null keeps the server default. */
    fun buildVoskWsUrl(
        base: String,
        lang: String? = null,
    ): String {
        val origin = base.substringBefore('?').trimEnd('/')
        require(lang == null || LANG_CODE.matches(lang)) { "invalid language code" }
        return origin +
            "?no_wake=1&partial=true" +
            "&content-type=audio/x-raw,+layout=(string)interleaved," +
            "+rate=(int)16000,+format=(string)S16LE,+channels=(int)1" +
            (lang?.let { "&lang=$it" } ?: "")
    }

    /** The server's error text for a non-zero status message, e.g. a language model not installed. */
    fun parseVoskError(text: String): String? =
        parseObject(text)?.let { obj ->
            obj["status"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "0" }?.let { status ->
                obj["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "语音识别出错（$status）"
            }
        }

    fun parseVoskMessage(text: String): VoskHypothesis? {
        val obj = parseObject(text) ?: return null
        val status = obj["status"]?.jsonPrimitive?.contentOrNull
        if (status != null && status != "0") return null
        val result = obj["result"] as? JsonObject ?: return null
        val hyps = result["hypotheses"]?.jsonArray ?: return null
        val first = hyps.firstOrNull() as? JsonObject ?: return null
        val transcript = first.str("transcript").trim()
        if (transcript.isEmpty()) return null
        val isFinal = result.bool("final")
        return VoskHypothesis(transcript = transcript, isFinal = isFinal)
    }

    private fun parseObject(raw: String): JsonObject? {
        if (raw.isBlank()) return null
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private fun JsonObject.str(key: String): String {
        if (key in secretKeys) return ""
        return this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun JsonObject.bool(key: String): Boolean {
        val p = this[key]?.jsonPrimitive ?: return false
        return p.booleanOrNull == true || p.content.equals("true", ignoreCase = true)
    }

    private fun quote(value: String): String =
        buildString {
            append('"')
            for (c in value) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('"')
        }

    private const val MAX_CONTROL_LENGTH = 80
    private const val PROVIDER_ERROR_PREVIEW = 220

    /** Politeness and "I want" lead-ins that carry no meaning for routing. */
    private val POLITE_PREFIX =
        Regex("""^(你)?(请你|请|麻烦你|麻烦|劳驾|能不能|能否|可不可以|可以)?(帮我|替我|给我|帮忙)?(我想要|我想|我要)?""")

    /** "在美团点一份外卖" / "用淘宝搜…" — a task inside a named app. */
    private val IN_APP_TASK = Regex("""^(在|用|去|到)\S{1,10}?(里|上)?(点|买|搜索?|查|看|订|叫|下单|找)""")

    private val QA_PREFIX =
        Regex("""^(如何|怎么|怎样|什么是|为什么|能否告诉|可不可以告诉|帮我看看|解释一下)""")
    private val CONTROL_PREFIX =
        Regex(
            """^(打开|开启|启动|进入|切换到|关掉|关闭|退出|回到|返回|点击|点一下|滑动|上滑|下滑|左滑|右滑|截图|锁屏|解锁|拨打|打电话|发短信|去设置)""",
        )
    private val CONTROL_VERB =
        Regex("""(打开|开启|启动|关掉|关闭).{0,20}(应用|设置|相机|蓝牙|Wi-?Fi|wifi|相册|浏览器|文件|电话|短信)""", RegexOption.IGNORE_CASE)
}

data class WakeResult(
    val ok: Boolean,
    val wakeId: String = "",
    val message: String = "",
    val error: String = "",
)

data class ChatApiResult(
    val ok: Boolean,
    val content: String = "",
    val error: String = "",
    /** What a reasoning model thought before answering, when the service sends it; never part of [content]. */
    val reasoning: String = "",
)

data class ProviderSummary(
    val configured: Boolean = false,
    val kind: String = "",
    val model: String = "",
    val endpoint: String = "",
    val authEnv: String = "",
)

data class LoopbackStatus(
    val httpOk: Boolean = false,
    val sttListening: Boolean = false,
    val agentWatch: Boolean = false,
    val agentRunner: Boolean = false,
    val provider: ProviderSummary = ProviderSummary(),
)

data class VoskHypothesis(
    val transcript: String,
    val isFinal: Boolean,
)
