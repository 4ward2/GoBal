package com.danielealbano.androidremotecontrolmcp.services.agent

import com.danielealbano.androidremotecontrolmcp.services.skill.PaymentGuard
import com.danielealbano.androidremotecontrolmcp.services.skill.ScreenBlockers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Why a task stops short of its goal, and how that is filed. */
internal class Halt(
    val status: RunStatus,
    val reason: String,
)

/**
 * The agent's view of the screen. A control is only ever judged by what the screen says *now*:
 * the model takes seconds to answer, pages finish loading meanwhile, and a node id read from an
 * older screen, another page of it or a tool's own result would otherwise be acted on unseen.
 */
internal class Eyes(
    private val tools: AgentTools,
    private val pause: suspend (Long) -> Unit,
) {
    /** The screen as last read, every page of it; empty when it could not be read. */
    var screen = ScreenText.EMPTY
        private set

    /** Reads the whole screen afresh. Returns its first page for the model, or null when it cannot be read. */
    suspend fun look(): String? {
        val first = tools.call(GET_SCREEN, JsonObject(emptyMap())).takeUnless { it.isError }?.text
        var seen = first?.let(ScreenText::parse) ?: ScreenText.EMPTY
        var pages = 1
        while (seen.nextCursor.isNotEmpty() && pages < MAX_PAGES) {
            val more = tools.call(GET_SCREEN, buildJsonObject { put("cursor", seen.nextCursor) })
            // A page that cannot be read ends the reading: what it held stays unknown, and unknown is not acted on.
            seen = if (more.isError) ScreenText(seen.packageName, seen.labels) else seen + ScreenText.parse(more.text)
            pages++
        }
        screen = seen
        return first
    }

    /** [look] once the screen has had a moment to settle after an action. */
    suspend fun lookAfterAction(): String? {
        pause(SETTLE_MS)
        return look()
    }

    /** The model read the screen itself: [paged] results add to what is known, a fresh one replaces it. */
    fun took(
        result: ToolReply,
        paged: Boolean,
    ) {
        if (result.isError) return
        val read = ScreenText.parse(result.text)
        screen = if (paged) screen + read else read
    }

    companion object {
        const val GET_SCREEN = "get_screen_state"
        private const val SETTLE_MS = 700L

        /** 200 controls a page: more pages than this is not a screen anyone can act on sensibly. */
        private const val MAX_PAGES = 10
    }
}

/**
 * What the agent must not do on its own: pay, get past a captcha or a login, or work in an app
 * the user has not allowed. Asked before every call and about every screen the task arrives at,
 * whichever way it got there.
 */
internal class Guards(
    private val tools: AgentTools,
    private val gate: AppGate,
    private val events: AgentEvents,
) {
    /** Why the saved skill that fits [goal] may not be replayed, or null. */
    suspend fun beforeSkill(goal: String): Halt? = refusedApp(tools.skillApps(goal))

    /** Why [move] must not be made on [screen], or null. */
    suspend fun before(
        move: AgentMove.Call,
        screen: ScreenText,
    ): Halt? {
        val node = move.args.text(NODE)
        // Everything written inside the control counts, not only its own label; and every tool
        // that touches a control counts, not only the taps (typing into one clicks it first).
        val words = screen.wordsWithin(node)
        // tap_text names its target by words instead of by control: the words are judged the same way.
        val said = if (move.tool == TAP_TEXT) move.args.text("text") else ""
        val paysByNode = node.isNotEmpty() && move.tool !in TOUCH_NOTHING && PaymentGuard.blocks(words)
        val pays = paysByNode || PaymentGuard.blocks(said)
        val target = (screen.labels[node] ?: words).ifEmpty { said }.take(TARGET_CHARS)
        return if (pays) Halt(RunStatus.HANDED_OVER, "到「$target」这一步了，付款和下单由你自己确认") else refusedApp(appsOf(move))
    }

    /** Why the task cannot go on from [screen], or null: a person is needed, or the app in front was not allowed. */
    suspend fun onScreen(screen: ScreenText): Halt? {
        val person = ScreenBlockers.reasonInText(screen.allText)
        return person?.let { Halt(RunStatus.HANDED_OVER, it) } ?: refusedApp(listOf(screen.packageName))
    }

    private fun appsOf(move: AgentMove.Call): List<String> =
        when (move.tool) {
            in OPENS -> listOf(move.args.text("package_id"))
            SkillFirst.SKILL_RUN -> tools.skillApps(move.args.text("name").ifEmpty { move.args.text("utterance") })
            else -> emptyList()
        }

    private suspend fun refusedApp(apps: List<String>): Halt? {
        val refused = apps.filter { it.isNotEmpty() }.firstOrNull { !allowed(it) } ?: return null
        return Halt(RunStatus.STOPPED, "你没有允许操作「${tools.appName(refused)}」（可在 设置 → 应用授权 里更改）")
    }

    private suspend fun allowed(app: String): Boolean {
        gate.settled(app)?.let { return it }
        events.waitingForApproval(true)
        return try {
            gate.ask(app)
        } finally {
            events.waitingForApproval(false)
        }
    }

    companion object {
        val OPENS = setOf("open_app", "shadow_open_app")
        private const val NODE = "node_id"
        private const val TAP_TEXT = "tap_text"
        private const val TARGET_CHARS = 20

        /** Tools that take a node but neither press nor fill it. */
        private val TOUCH_NOTHING = setOf("scroll_to_node")
    }
}

/**
 * Why the model did not answer, in words that are safe to keep. A service's own error text can
 * quote part of the key or the account: it never goes into the task history, a notification or
 * the conversation.
 */
object ModelTrouble {
    private val CODE = Regex("HTTP (\\d{3})")
    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val NOT_FOUND = 404
    private const val TOO_MANY = 429
    private const val SERVER_ERRORS = 500

    fun plain(error: String): String {
        val code =
            CODE
                .find(error)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        return when {
            code == null -> "模型没有回应：连不上，或等太久了"
            code == UNAUTHORIZED || code == FORBIDDEN -> "模型没有回应：密钥不对或已经失效（HTTP $code）"
            code == NOT_FOUND -> "模型没有回应：模型名或接口地址不对（HTTP $code）"
            code == TOO_MANY -> "模型没有回应：请求太频繁，或额度用完了（HTTP $code）"
            code >= SERVER_ERRORS -> "模型没有回应：服务商那边出错了（HTTP $code）"
            else -> "模型没有回应（HTTP $code）"
        }
    }
}

internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
