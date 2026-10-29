package com.danielealbano.androidremotecontrolmcp.services.remote.hooks

import com.danielealbano.androidremotecontrolmcp.services.chat.ChatMessage
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where a goal stands. */
enum class GoalState {
    /** The agent is on a round. */
    WORKING,

    /** The round ended; the phone's model is checking it against the criteria. */
    JUDGING,

    /** The agent stopped on a usage limit, a busy model or a crash; waiting for it to carry on. */
    INTERRUPTED,
    DONE,
    STOPPED,
    OUT_OF_ROUNDS,
    OUT_OF_TIME,

    /** The check itself could not be made, or the next round could not be sent. */
    FAILED,
    ;

    val active: Boolean get() = this == WORKING || this == JUDGING || this == INTERRUPTED
}

/** 「跑到达成为止」 for one agent on one computer. */
@Serializable
data class GoalRun(
    val id: String,
    val computerId: String,
    val computerName: String,
    val agentId: String,
    val agentName: String,
    val goal: String,
    val criteria: String,
    val maxRounds: Int,
    /** Epoch millis after which no new round is started. */
    val deadline: Long,
    val startedAt: Long,
    val updatedAt: Long,
    val round: Int = 1,
    val state: GoalState = GoalState.WORKING,
    /** What the last check found still missing (told to the agent), or why it stopped. */
    val missing: String = "",
    /** Why it paused or stopped, when that is not [missing]. */
    val note: String = "",
)

/** What the phone's model said about one round. */
data class Verdict(
    val done: Boolean,
    val missing: String,
)

/** The words said to the agent and to the checking model, and reading the check's answer. Pure. */
object GoalJudge {
    private const val REPLY_MAX = 6000
    private const val MISSING_MAX = 400
    private const val FIRST_ROUND = "（这是第一轮，之前没有验收反馈）"
    private const val UNCLEAR = "没说清楚还差什么，请对照达成标准逐条检查"
    private val json = Json { ignoreUnknownKeys = true }

    fun kickoff(
        goal: String,
        criteria: String,
    ): String =
        "【目标模式】目标：$goal\n达成标准：$criteria\n" +
            "请一直做到满足全部标准为止。每轮停下时，说明这一轮做了什么、拿什么证明、还差什么。"

    fun followUp(
        round: Int,
        missing: String,
    ): String = "【目标模式 第${round}轮】还没达成：$missing\n请接着做。停下时说明做了什么、拿什么证明、还差什么。"

    /** What the phone's model is asked about [run]'s latest round, with what it found missing last time. */
    fun messages(
        run: GoalRun,
        reply: String,
    ): List<ChatMessage> {
        val system =
            "你是验收员，只根据代理这一轮的回复判断目标是否已经按标准达成。代理自己说「做完了」不算数，" +
                "要看它给出的结果和证据是否满足每一条标准；看不出证据就算没达成。" +
                "只回一个 JSON，不要别的字：{\"done\": true 或 false, \"missing\": \"还差什么，一两句，直接写给代理看\"}"
        val before = if (run.missing.isBlank()) FIRST_ROUND else "上一轮验收告诉它还差：${run.missing}"
        val user =
            "目标：${run.goal}\n达成标准：${run.criteria}\n现在是第 ${run.round} 轮。$before\n\n" +
                "代理这一轮的回复：\n${reply.takeLast(REPLY_MAX)}"
        return listOf(ChatMessage("system", system), ChatMessage("user", user))
    }

    /** The check's answer, or null when it is not the JSON asked for. */
    fun verdict(answer: String): Verdict? {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        val body = if (start in 0 until end) answer.substring(start, end + 1) else ""
        val node = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val done = node?.flag("done") ?: return null
        val missing =
            node["missing"]
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
                .trim()
                .take(MISSING_MAX)
        return Verdict(done, if (done) "" else missing.ifBlank { UNCLEAR })
    }

    private fun JsonObject.flag(key: String): Boolean? {
        val value = runCatching { get(key)?.jsonPrimitive }.getOrNull()
        return value?.booleanOrNull
    }

    /** The agent's last answer in full: its pieces share one message id. */
    fun lastReply(items: List<RemoteItem>): String {
        val answers = items.filterIsInstance<RemoteItem.Assistant>()
        val id = answers.lastOrNull()?.messageId ?: return ""
        return answers.takeLastWhile { it.messageId == id }.joinToString("") { it.text }
    }
}
