package com.danielealbano.androidremotecontrolmcp.services.agent

import com.danielealbano.androidremotecontrolmcp.services.chat.ChatApiResult
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatMessage
import com.danielealbano.androidremotecontrolmcp.services.skill.ScreenBlockers
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What a tool answered. */
data class ToolReply(
    val text: String,
    val isError: Boolean = false,
)

/** The phone's tools as the agent uses them: the same ones MCP clients get, called in-process. */
interface AgentTools {
    /** Null when the tools can act; otherwise what the user has to switch on first. */
    fun notReady(): String?

    fun specs(): List<ToolSpec>

    suspend fun call(
        tool: String,
        args: JsonObject,
    ): ToolReply

    /** The app's name for a package, or the package itself when it is not installed. */
    fun appName(packageName: String): String

    /** The names of the user's saved skills. */
    fun skills(): List<String>

    /** The apps the saved skill [nameOrRequest] (its name, or a sentence that runs it) opens; none if no skill fits. */
    fun skillApps(nameOrRequest: String): List<String>
}

/** The language model deciding each step. */
fun interface AgentBrain {
    suspend fun reply(messages: List<ChatMessage>): ChatApiResult
}

/** Whether the agent may work in an app (once, always or not). */
interface AppGate {
    /** The answer when it needs no asking (always allowed, switched off, or nobody's app like the home screen). */
    fun settled(packageName: String): Boolean?

    /** Asks the user and returns the answer. */
    suspend fun ask(packageName: String): Boolean
}

/** What the agent reports while it works, and where the user's extra sentences come from. */
interface AgentEvents {
    fun step(step: AgentStep)

    /** True while the user is being asked to allow an app. */
    fun waitingForApproval(waiting: Boolean)

    fun archived(skillName: String)

    /** The agent wrote [text] down for later; kept with the task so a stop does not lose it. */
    fun noted(text: String) = Unit

    /** The plan changed, or a step of it moved on. */
    fun planned(plan: List<PlanItem>) = Unit

    /** The task's map changed. */
    fun mapped(tree: List<TreeLine>) = Unit

    /** Sentences the user added since the last call ("补充"); each is handed out once. */
    fun takeNotes(): List<String>
}

/** How a task ended and what to tell the user. */
data class AgentOutcome(
    val status: RunStatus,
    val result: String,
)

/**
 * Does one phone task: a saved skill first (no model needed), otherwise step by step with the
 * model choosing each tool call from what the screen shows. It stops, and says why, before
 * anything a person must do: paying, a captcha, a login, or an app the user did not allow.
 *
 * Runs entirely inside the app on the accessibility service: no root and no helper process.
 */
class PhoneAgent(
    private val tools: AgentTools,
    private val brain: AgentBrain,
    gate: AppGate,
    private val events: AgentEvents,
    pause: suspend (Long) -> Unit = { delay(it) },
    /**
     * Nothing that changes the screen is done before the model has made a plan. Off only where a
     * script stands in for the model.
     */
    private val planFirst: Boolean = false,
) {
    private val mapper = Mapper(tools, events)
    private val planner = Planner(events, mapper)
    private val transcript = AgentTranscript(planner.board)
    private val ending = TaskEnding(tools, events, planner.board)
    private val eyes = Eyes(tools, pause)
    private val guards = Guards(tools, gate, events)
    private val blockers = Blockers(guards, Cooldown(tools, events, pause), mapper, eyes)
    private val launches = Launches()

    /** The tools offered to the model: the ones for operating apps, not files, camera or settings. */
    private val offered: List<ToolSpec> by lazy { tools.specs().filter { it.name in OFFERED } }
    private var badReplies = 0

    /** Exchanges with the model so far, and the one whose reply last could not be used. */
    private var taken = 0
    private var lastBad = -1

    suspend fun run(goal: String): AgentOutcome {
        val notReady = tools.notReady()?.let { AgentOutcome(RunStatus.FAILED, it) }
        // A saved skill opens its apps without the model: they are asked about before it starts.
        val blocked = notReady ?: guards.beforeSkill(goal)?.let { AgentOutcome(it.status, it.reason) }
        val replay = if (blocked == null) SkillFirst(tools, events).replay(goal) else Replay(blocked)
        ending.replayed = replay.thenRead
        // A replayed lookup only reads the screen it was taken to: there is nothing to plan.
        planner.planFirst = planFirst && !replay.thenRead
        return replay.outcome ?: stepByStep(goal, replay.note)
    }

    /** No saved skill did it: the model decides each step until the task ends or the steps run out. */
    private suspend fun stepByStep(
        goal: String,
        note: String,
    ): AgentOutcome {
        tools.call(TASK_BEGIN, buildJsonObject { put("goal", goal) })
        transcript.start(AgentProtocol.systemPrompt(offered, tools.skills()), goal + note)
        var outcome: AgentOutcome? = null
        // The limit is asked each time round: a plan, once made, gives the task more room.
        while (outcome == null && taken < planner.budget()) {
            taken++
            outcome = step()
        }
        return outcome ?: ending.finish(ok = false, summary = OUT_OF_STEPS, params = emptyMap())
    }

    /** One exchange with the model; null to go on, or how the task ended. */
    private suspend fun step(): AgentOutcome? {
        planner.brief(transcript)
        val answer = brain.reply(transcript.messages())
        val move = if (answer.ok) AgentProtocol.parse(answer.content) else null
        if (answer.ok) mapper.at(placeIn(answer.content), eyes.screen.allText)
        return when (move) {
            null -> {
                ending.finish(ok = false, summary = ModelTrouble.plain(answer.error), params = emptyMap())
            }

            is AgentMove.Invalid -> {
                retry(answer.content, move.problem)
            }

            is AgentMove.Done -> {
                finishing(answer.content, move)
            }

            is AgentMove.HandOver -> {
                ending.end(RunStatus.HANDED_OVER, move.reason.ifBlank { "这一步需要你来" })
            }

            is AgentMove.Plan -> {
                told(answer.content, planner.adopt(move))
            }

            is AgentMove.Explore -> {
                told(answer.content, mapper.search.explore(move))
            }

            is AgentMove.Close -> {
                told(answer.content, mapper.search.close(move))
            }

            is AgentMove.Goto -> {
                jumped(answer.content, move)
            }

            is AgentMove.Note -> {
                ending.readingStarts()
                told(answer.content, planner.note(move))
            }

            is AgentMove.Call -> {
                act(answer.content, move)
            }
        }
    }

    /** The model is answered with [words] (and [screen], when there is one to show) and the task goes on. */
    private fun told(
        reply: String,
        words: String,
        screen: String? = null,
    ): AgentOutcome? {
        transcript.exchange(reply, words, screen?.take(SCREEN_CHARS))
        return null
    }

    /** The phone is taken back to a place of the map, and the model shown where it landed. */
    private suspend fun jumped(
        reply: String,
        move: AgentMove.Goto,
    ): AgentOutcome? {
        val words = mapper.goto(move.at)
        val seen = eyes.lookAfterAction()
        val halt = blockers.screen(eyes.screen)
        told(reply, words, blockers.freshScreen() ?: seen)
        return halt?.let { ending.halted(it) }
    }

    private suspend fun retry(
        reply: String,
        problem: String,
    ): AgentOutcome? {
        // Only replies that cannot be used one after another end the task; a slip now and then does not.
        badReplies = if (lastBad == taken - 1) badReplies + 1 else 1
        lastBad = taken
        if (badReplies < MAX_BAD_REPLIES) return told(reply, AgentProtocol.retryNotice(problem))
        return ending.finish(ok = false, summary = "模型的回答看不懂，换个模型试试", params = emptyMap())
    }

    /**
     * The model says the task is over. A lookup with places on its map still open is sent back to
     * them once; a finished one tells the user what was left unlooked at, if anything.
     */
    private suspend fun finishing(
        reply: String,
        move: AgentMove.Done,
    ): AgentOutcome? {
        val sentBack = mapper.search.beforeDone(move.ok)
        if (sentBack != null) return told(reply, sentBack)
        planner.closing(move.ok)
        return ending.finish(move.ok, mapper.honest(move.summary, move.ok), move.params, move.drop)
    }

    private suspend fun act(
        reply: String,
        move: AgentMove.Call,
    ): AgentOutcome? {
        val refused =
            when {
                offered.none { it.name == move.tool } -> "There is no tool named ${move.tool}. Use one from the list."
                planner.mustPlan(move.tool in CHANGES_SCREEN) -> Planner.PLAN_FIRST
                else -> null
            }
        if (refused != null) return told(reply, refused)
        // A call that names a control is judged on the screen as it is this instant, not as the
        // model last saw it.
        val node = move.args.text("node_id")
        val seen = if (node.isEmpty()) null else eyes.look()
        val stop = planner.acting(move, StepLabels.of(move.tool, move.args, eyes.screen, tools::appName))
        val onScreen = if (node.isEmpty()) null else blockers.screen(eyes.screen)
        val halt = stop ?: onScreen ?: guards.before(move, eyes.screen)
        return when {
            halt != null -> ending.halted(halt)

            // The control is not on the screen (any more): nothing is done, the model is shown the screen as it is.
            node.isNotEmpty() && !eyes.screen.has(node) -> told(reply, notThere(node), blockers.freshScreen() ?: seen)

            else -> perform(reply, move)
        }
    }

    /** Calls the tool, reads the screen it left behind, and stops if that screen needs a person or a permission. */
    private suspend fun perform(
        reply: String,
        move: AgentMove.Call,
    ): AgentOutcome? {
        val label = StepLabels.of(move.tool, move.args, eyes.screen, tools::appName)
        // An app is opened at its first screen the first time in a task (that is where a replay will
        // start it); after that it is brought back, since starting an app cold again and again is
        // what gets a captcha put up.
        val fresh = move.tool == OPEN_APP && launches.fresh(move.args.text("package_id"))
        val args = if (move.tool == OPEN_APP) JsonObject(move.args + (FRESH to JsonPrimitive(fresh))) else move.args
        val before = eyes.screen
        val result = tools.call(move.tool, args)
        events.step(AgentStep(label, ok = !result.isError))
        if (fresh && !result.isError) mapper.restarted()
        val isScreen = move.tool == Eyes.GET_SCREEN
        if (isScreen) eyes.took(result, paged = move.args.text("cursor").isNotBlank())
        val after = if (isScreen) result.text else lookAgain(move.tool)
        val scrolledOn = move.tool == SCROLL && move.args.text("direction") in ONWARD
        val ended = mapper.search.sawScreen(before, eyes.screen, scrolledOn && !result.isError)
        val said = if (isScreen) "Screen:" else "Result of ${move.tool}: ${result.text.take(RESULT_CHARS)}"
        val observation = said + ended
        val halt = blockers.screen(eyes.screen)
        transcript.exchange(reply, observation, (blockers.freshScreen() ?: after)?.take(SCREEN_CHARS))
        return halt?.let { ending.halted(it) }
    }

    /** After anything that can change the screen, read it again so the model need not ask. */
    private suspend fun lookAgain(tool: String): String? = if (tool in REREAD) eyes.lookAfterAction() else null

    companion object {
        const val MAX_STEPS = Planner.BASE_STEPS
        const val OUT_OF_STEPS = "做了很多步还没完成，先停下来了"
        private const val MAX_BAD_REPLIES = 3
        private const val RESULT_CHARS = 1_500
        private const val SCREEN_CHARS = 12_000
        private const val TASK_BEGIN = "task_begin"
        private const val OPEN_APP = "open_app"
        private const val FRESH = "fresh"

        private const val NOW_SHOWN = "on the screen now. This is the current screen:"

        private fun notThere(node: String) = "Nothing was done: there is no control $node $NOW_SHOWN"

        private const val SCROLL = "scroll"

        /** Directions that move a list on towards its end. */
        private val ONWARD = setOf("down", "right")

        // Left out on purpose: open_uri (a link can open any app, a payment page included, unasked)
        // and swipe (a swipe that goes nowhere is a tap at a coordinate, which no guard sees).
        private val TAPS = setOf("click_node", "tap_node", "long_click_node", "tap_text")
        private val OPENS = Guards.OPENS
        private val TYPES = setOf("type_append_text", "type_insert_text", "type_replace_text", "type_clear_text")
        private val MOVES = setOf("scroll", "scroll_to_node", "press_back", "press_home", "dismiss_keyboard")
        private val FILLS = setOf("vault_fill", "system_autofill")
        private val CHANGES_SCREEN = TAPS + OPENS + TYPES + MOVES + FILLS
        private val WAITS = setOf("wait_for_idle", "wait_for_node")
        private val READS =
            setOf(Eyes.GET_SCREEN, "list_apps", "vault_list_slots", "task_status", SkillFirst.SKILL_RUN) + WAITS

        /** After these the screen may be another one: a wait returns when it changed, a skill taps through apps. */
        private val REREAD = CHANGES_SCREEN + WAITS + SkillFirst.SKILL_RUN

        /** Everything the model may call. */
        val OFFERED: Set<String> = CHANGES_SCREEN + READS
    }
}

/** What trying a saved skill came to: the task's outcome, or a note for the model to carry on with. */
internal class Replay(
    val outcome: AgentOutcome?,
    val note: String = "",
    /** The skill went through and only navigates: the model reads the screen it ended on. */
    val thenRead: Boolean = false,
)

/** A saved skill that matches the request is replayed without asking the model at all. */
internal class SkillFirst(
    private val tools: AgentTools,
    private val events: AgentEvents,
) {
    suspend fun replay(goal: String): Replay {
        val reply = tools.call(SKILL_RUN, buildJsonObject { put("utterance", goal) })
        val body = reply.text.substringAfter(STATUS_PREFIX, "").trim()
        val status = body.substringBefore('\n').trim()
        val detail = body.substringAfter('\n', "").trim()
        if (status in REPLAYED) events.step(AgentStep(STEP, ok = status != "failed"))
        // A captcha stopped the replay: the task goes on step by step, which cools the app down first.
        val stoppedAt = if (status == "handed_over" && ScreenBlockers.CAPTCHA_REASON in detail) "failed" else status
        return when (stoppedAt) {
            "done" -> if (THEN_READ in body) Replay(null, READ_ON, thenRead = true) else finished(detail)
            "handed_over" -> Replay(AgentOutcome(RunStatus.HANDED_OVER, detail.ifEmpty { "做到需要你确认的一步了" }))
            "failed" -> Replay(null, "\n\n$STOPPED\n$detail\nCarry on from the current screen.")
            else -> Replay(null)
        }
    }

    private fun finished(detail: String) = Replay(AgentOutcome(RunStatus.DONE, detail.ifEmpty { "按保存的技能做完了" }))

    companion object {
        const val SKILL_RUN = "skill_run"
        private const val THEN_READ = "then_read: true"
        private const val READ_ON =
            "\n\nA saved skill for this request has just been replayed and the phone is on the screen it leads " +
                "to. Do not find your way there again: read this screen and finish the request from here, " +
                "going further (opening an item for its options, scrolling) only as far as the request needs."
        private const val STATUS_PREFIX = "status:"
        private const val STEP = "重放技能"
        private const val STOPPED = "A saved skill for this request stopped part-way:"
        private val REPLAYED = setOf("done", "handed_over", "failed")
    }
}

/** Ending a task: the journal is closed, and a task that went well is archived as a skill. */
internal class TaskEnding(
    private val tools: AgentTools,
    private val events: AgentEvents,
    private val board: TaskBoard = TaskBoard(),
) {
    /**
     * A saved skill brought the task to its screen: whatever is done from there depends on what the
     * screen shows today, so the skill is not recorded over.
     */
    var replayed = false

    /** How many steps were recorded when the reading began; the saved skill ends there. */
    private var wayThere: Int? = null

    /**
     * The model starts writing down what it sees: from here on what it opens depends on today's
     * screen, so the recording of the way there ends at this point. Only the first call counts.
     */
    suspend fun readingStarts() {
        if (wayThere != null) return
        val status = tools.call(TASK_STATUS, JsonObject(emptyMap())).text
        wayThere = RECORDED
            .find(status)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: Int.MAX_VALUE
    }

    /** The task is over by the model's own account ([ok] or not), with [summary] for the user. */
    suspend fun finish(
        ok: Boolean,
        summary: String,
        params: Map<String, String>,
        drop: List<Int> = emptyList(),
    ): AgentOutcome {
        val archived = close(ok && !replayed, params, drop)
        if (archived.isNotEmpty()) events.archived(archived)
        val text = summary.ifBlank { if (ok) "做完了" else "没有做成" }
        return AgentOutcome(if (ok) RunStatus.DONE else RunStatus.FAILED, text)
    }

    /** The task stops at something a person must do, or the user must allow. */
    suspend fun halted(halt: Halt): AgentOutcome = end(halt.status, halt.reason)

    /** The task stops short of its goal for a reason that is not a failure of the model. */
    suspend fun end(
        status: RunStatus,
        result: String,
    ): AgentOutcome {
        close(success = false, params = emptyMap(), drop = emptyList())
        return AgentOutcome(status, result)
    }

    /** Closes the task journal and returns the name of the skill it was archived as, if any. */
    private suspend fun close(
        success: Boolean,
        params: Map<String, String>,
        drop: List<Int>,
    ): String {
        val args =
            buildJsonObject {
                put("success", success)
                // Notes mean the request was answered by reading: a replay must end in a reading too.
                put("then_read", board.hasNotes)
                wayThere?.takeIf { it != Int.MAX_VALUE }?.let { put("keep", it) }
                putJsonArray("drop") { drop.forEach { add(JsonPrimitive(it)) } }
                putJsonObject("params") { params.forEach { (key, value) -> put(key, value) } }
            }
        val lines = tools.call(TASK_END, args).text.lines()
        val archived = lines.firstOrNull { it.startsWith(ARCHIVED_PREFIX) }
        // A task that went well but could not be saved says why, where the user sees it: the same
        // request will need the model again, and the reason is what to fix.
        val whyNot = lines.firstOrNull { it.startsWith(NOT_SAVED_PREFIX) }
        // Too few steps to be worth a skill is no fault and is not reported.
        val worthSaying = whyNot != null && TOO_SHORT !in whyNot
        if (success && archived == null && worthSaying) {
            events.step(AgentStep(whyNot.orEmpty().take(WHY_CHARS), ok = false))
        }
        return archived?.removePrefix(ARCHIVED_PREFIX)?.trim().orEmpty()
    }

    private companion object {
        const val TASK_END = "task_end"
        const val ARCHIVED_PREFIX = "archived:"
        const val NOT_SAVED_PREFIX = "没有存成技能"
        const val TOO_SHORT = "步骤太少"
        const val TASK_STATUS = "task_status"
        val RECORDED = Regex("steps: (\\d+)")
        const val WHY_CHARS = 120
    }
}

/**
 * The conversation with the model, kept small however long the task runs: only the newest screen
 * is sent in full, the last few turns keep their words, and everything before them is folded
 * into one line each. The plan and the notes ride along with the newest message.
 */
internal class AgentTranscript(
    private val board: TaskBoard = TaskBoard(),
) {
    private class Turn(
        val reply: String,
        val observation: String,
        val screen: String?,
    )

    private var system = ""
    private var goal = ""
    private val turns = mutableListOf<Turn>()
    private val notes = mutableListOf<Pair<Int, String>>()

    fun start(
        systemPrompt: String,
        request: String,
    ) {
        system = systemPrompt
        goal = request
    }

    fun exchange(
        reply: String,
        observation: String,
        screen: String?,
    ) {
        turns += Turn(reply, observation, screen)
    }

    /** Something the user added while the task runs; the model sees it from the next step on. */
    fun note(text: String) {
        notes += turns.size to "The user adds: $text"
    }

    /** Something the harness tells the model before its next turn. */
    fun hint(text: String) {
        notes += turns.size to "Harness: $text"
    }

    fun messages(): List<ChatMessage> {
        val firstKept = (turns.size - KEPT_TURNS).coerceAtLeast(0)
        val opening = "Request: $goal" + folded(firstKept) + (0..firstKept).joinToString("") { notesAt(it) }
        val out = mutableListOf(ChatMessage("system", system), ChatMessage("user", opening))
        for (index in firstKept until turns.size) {
            val turn = turns[index]
            val newest = index == turns.lastIndex
            val shown = turn.screen?.let { if (newest) "\n$it" else "\n(an earlier screen, omitted)" }.orEmpty()
            out += ChatMessage("assistant", turn.reply)
            out += ChatMessage("user", turn.observation + shown + notesAt(index + 1))
        }
        val pinned = board.render()
        if (pinned.isNotEmpty()) {
            val last = out.last()
            out[out.lastIndex] = ChatMessage(last.role, last.text + "\n\n" + pinned)
        }
        return out
    }

    /** The turns before [firstKept], a line each: what was done and what came of it. */
    private fun folded(firstKept: Int): String {
        if (firstKept == 0) return ""
        val lines =
            turns.take(firstKept).mapIndexed { index, turn ->
                val did =
                    AgentProtocol
                        .firstObject(turn.reply)
                        ?.get("action")
                        ?.toString()
                        ?.trim('"') ?: "?"
                "${index + 1}. $did -> ${turn.observation.lineSequence().first().take(FOLDED_CHARS)}"
            }
        return "\n\nEarlier steps (their screens are gone):\n" + lines.joinToString("\n")
    }

    private fun notesAt(turn: Int): String = notes.filter { it.first == turn }.joinToString("") { "\n\n${it.second}" }

    private companion object {
        /** Turns sent with their own words; older ones are folded. */
        const val KEPT_TURNS = 8
        const val FOLDED_CHARS = 110
    }
}
