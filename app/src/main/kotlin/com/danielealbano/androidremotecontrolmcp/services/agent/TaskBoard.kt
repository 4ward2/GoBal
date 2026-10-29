package com.danielealbano.androidremotecontrolmcp.services.agent

/**
 * What a long task keeps in front of the model at every turn: the plan it made, which step it is
 * on, and what it wrote down on the way. Screens are not kept from one turn to the next, so
 * anything seen and needed later only survives here.
 */
internal class TaskBoard {
    var steps: List<String> = emptyList()
        private set

    /** The step in progress, counted from 1; 0 before the first one is named. */
    var at: Int = 0
        private set

    /** What each finished step of the current plan achieved, in the model's words, by step number. */
    private val outcomes = mutableMapOf<Int, String>()

    /** Steps finished under a plan that was changed afterwards: kept, so the record of the task is whole. */
    private val earlier = mutableListOf<PlanItem>()

    /** The task ended well: every step of the plan counts as done. */
    private var completed = false

    private val notes = mutableListOf<String>()
    private var noteChars = 0

    /** The map of places, when the task keeps one; drawn under the plan and the notes. */
    var map: () -> String = { "" }

    /** True when something was written down on the map. */
    var mapHasNotes: () -> Boolean = { false }

    val planned: Boolean get() = steps.isNotEmpty()

    /** True once anything was written down: the task read something off the screen for the user. */
    val hasNotes: Boolean get() = notes.isNotEmpty() || mapHasNotes()

    /**
     * Takes [newSteps] as the plan; a changed plan starts again from its first step. The steps
     * the old plan had finished are archived with what they achieved, not thrown away.
     */
    fun adopt(newSteps: List<String>) {
        earlier += items().filter { it.state == PlanState.DONE && !it.earlier }.map { it.copy(earlier = true) }
        steps = newSteps.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_STEPS)
        outcomes.clear()
        at = 0
    }

    /**
     * Says the model is now on [step], having finished the one before with [outcome]; true when
     * that is a different step of the plan than before.
     */
    fun moveTo(
        step: Int,
        outcome: String = "",
    ): Boolean {
        val moved = step in 1..steps.size && step != at
        if (moved && at in 1 until step && outcome.isNotBlank()) outcomes[at] = outcome.trim().take(OUTCOME_CHARS)
        if (moved) at = step
        return moved
    }

    /** The task ended; when [ok], the whole plan was carried out. */
    fun finish(ok: Boolean) {
        completed = ok
    }

    /** The plan as the task's record shows it: what was done before a change, then the current steps. */
    fun items(): List<PlanItem> =
        earlier +
            steps.mapIndexed { index, text ->
                val number = index + 1
                val state =
                    when {
                        completed || number < at -> PlanState.DONE
                        number == at -> PlanState.NOW
                        else -> PlanState.TODO
                    }
                PlanItem(text, state, outcomes[number].orEmpty())
            }

    /** Keeps [text]; false when the notes are full and it was not kept. */
    fun write(text: String): Boolean {
        val clean = text.trim().take(NOTE_CHARS)
        val fits = clean.isNotEmpty() && noteChars + clean.length <= ALL_NOTES_CHARS
        if (fits) {
            notes += clean
            noteChars += clean.length
        }
        return fits
    }

    /** The plan with its progress and the notes, as the model reads them; empty while there is neither. */
    fun render(): String =
        buildString {
            if (earlier.isNotEmpty()) {
                appendLine("Done before the plan was changed:")
                earlier.forEach { appendLine("- ${it.text}${said(it.outcome)}") }
            }
            if (planned) {
                appendLine("Plan:")
                items().filterNot { it.earlier }.forEachIndexed { index, item ->
                    appendLine("${MARKS.getValue(item.state)} ${index + 1}. ${item.text}${said(item.outcome)}")
                }
            }
            if (notes.isNotEmpty()) {
                appendLine("Notes you wrote:")
                notes.forEach { appendLine("- $it") }
            }
            appendLine(map())
        }.trimEnd()

    /** What the model is told after it finished step [finished] (with [outcome]) and moved on. */
    fun rethinkAfter(
        finished: Int,
        outcome: String,
    ): String {
        val said = if (outcome.isBlank()) " (say in outcome what a step achieved when you move on)" else ""
        return "Step $finished is archived as done$said. Before acting on step $at, think again: does the " +
            "rest of the plan still fit what you have seen? If not, reply with plan and the changed steps."
    }

    private fun said(outcome: String): String = if (outcome.isEmpty()) "" else " -> $outcome"

    companion object {
        const val MAX_STEPS = 8
        private const val NOTE_CHARS = 1_200
        private const val ALL_NOTES_CHARS = 8_000
        private const val OUTCOME_CHARS = 160
        private val MARKS = mapOf(PlanState.DONE to "[done]", PlanState.NOW to "[now]", PlanState.TODO to "[todo]")
    }
}

/**
 * The harness around the model for tasks that take many steps: it holds the [board], gives the
 * task more room once there is a plan, and speaks up when the model acts without a plan, repeats
 * itself, or has gone a while without checking its plan against the screen.
 */
internal class Planner(
    private val events: AgentEvents,
    private val mapper: Mapper? = null,
) {
    val board =
        TaskBoard().also { board ->
            mapper?.let { map ->
                board.map = map::render
                board.mapHasNotes = { map.tree.views().any { it.facts.isNotEmpty() } }
            }
        }
    private var actions = 0
    private var actionsWithoutPlan = 0
    private var lastAction = ""
    private var repeats = 0
    private var askedForPlan = false
    private var pending: String? = null
    private val pokes = Pokes()
    private val thrash = Thrash()
    private var drawnTree: List<TreeLine>? = null

    /** Whether the model must make a plan before it may change the screen. */
    var planFirst = false

    /** How many exchanges with the model the task may take: more once it has a plan to work through. */
    fun budget(): Int {
        val marked = mapper?.tree?.views()?.count { it.wanted } ?: 0
        val room = BASE_STEPS + board.steps.size * STEPS_PER_PLAN_STEP + marked * STEPS_PER_PLACE
        return room.coerceAtMost(MOST_STEPS)
    }

    /** The model set or changed its plan; returns what it is told. */
    fun adopt(move: AgentMove.Plan): String {
        val changed = board.planned
        board.adopt(move.steps)
        if (!board.planned) return "The plan had no steps. Reply with plan and a list of short steps."
        events.planned(board.items())
        val listed = board.steps.mapIndexed { index, step -> "${index + 1}.$step" }.joinToString("；")
        events.step(AgentStep((if (changed) "改计划：" else "定计划：") + listed))
        return "Plan ${if (changed) "changed" else "saved"}. Start with step 1 and put \"step\": 1 in your reply."
    }

    /** The model wrote something down; returns what it is told. */
    fun note(move: AgentMove.Note): String {
        val map = mapper
        return when {
            // What the model notes about a place the screen does not show was not seen, and is not kept.
            map != null && map.doubted && move.about.isBlank() -> "Nothing was noted. " + map.takeHint().orEmpty()

            // Once there is a map, what is seen goes into it, at the place it belongs to.
            map != null && map.search.keeps(move) -> map.search.note(move)

            else -> plainNote(move)
        }
    }

    /** A note kept on the board, apart from any map. */
    private fun plainNote(move: AgentMove.Note): String {
        val kept = board.write(move.text.trim())
        if (kept) {
            events.step(
                AgentStep(
                    "记下：" +
                        move.text
                            .trim()
                            .replace('\n', ' ')
                            .take(LABEL_CHARS),
                ),
            )
            events.noted(move.text.trim())
        }
        return if (kept) "Noted." else "Not noted: the notes are full or the text was empty. Finish with what you have."
    }

    /**
     * The model is about to call a tool: follows its place in the plan and watches for it going in
     * circles. Returns why the task must stop instead, when it keeps going back and forth.
     */
    fun acting(
        move: AgentMove.Call,
        label: String,
    ): Halt? {
        actions++
        val stop = thrash.after(move.tool)
        val circling = move.tool in POKES && pokes.again(label)
        if (!board.planned) actionsWithoutPlan++
        followPlan(move)
        val action = move.tool + move.args
        repeats = if (action == lastAction) repeats + 1 else 1
        lastAction = action
        pending =
            when {
                thrash.takeWarning() -> {
                    Thrash.WARN
                }

                repeats >= REPEATS -> {
                    REPEATING.also { repeats = 0 }
                }

                circling -> {
                    CIRCLING
                }

                !board.planned && actionsWithoutPlan >= PLAN_AFTER && !askedForPlan -> {
                    askedForPlan = true
                    NO_PLAN
                }

                board.planned && actions % REVIEW_EVERY == 0 -> {
                    CHECKPOINT
                }

                else -> {
                    pending
                }
            }
        return stop
    }

    /**
     * The model says which step of its plan it is on: a new one is shown, and when it moved on from
     * a finished step, that step's outcome is on record and the rest of the plan is to be thought over.
     */
    private fun followPlan(move: AgentMove.Call) {
        val from = board.at
        if (!board.moveTo(move.step, move.outcome)) return
        events.step(AgentStep("第${board.at}步：${board.steps[board.at - 1]}"))
        events.planned(board.items())
        if (from in 1 until board.at) pending = board.rethinkAfter(from, move.outcome)
    }

    /** True when the model may not yet do something that [changesScreen]: there is no plan. */
    fun mustPlan(changesScreen: Boolean): Boolean = planFirst && changesScreen && !board.planned

    /** The task is over by the model's account; when [ok], its whole plan was carried out. */
    fun closing(ok: Boolean) {
        board.finish(ok)
        if (board.planned) events.planned(board.items())
    }

    /** Before the model's next turn: what the user added since the last one, and what the harness has to say. */
    fun brief(transcript: AgentTranscript) {
        events.takeNotes().forEach { transcript.note(it) }
        takeHint()?.let { transcript.hint(it) }
        mapper?.takeHint()?.let { transcript.hint(it) }
        mapper?.let { map ->
            val tree = TreeLines.of(map.tree.views())
            if (tree != drawnTree) {
                drawnTree = tree
                events.mapped(tree)
            }
        }
    }

    /** What the harness has to say before the model's next turn; handed out once. */
    fun takeHint(): String? = pending.also { pending = null }

    companion object {
        const val BASE_STEPS = 30
        const val MOST_STEPS = 90
        private const val STEPS_PER_PLAN_STEP = 12
        private const val STEPS_PER_PLACE = 4
        private const val PLAN_AFTER = 3
        private const val REPEATS = 3
        private const val REVIEW_EVERY = 10
        private const val LABEL_CHARS = 24
        private val POKES = setOf("click_node", "tap_node", "tap_text")
        const val CIRCLING =
            "You have tapped the same control three times lately without getting where you want. If the " +
                "words you want are only part of that control, use tap_text with exactly those words; " +
                "otherwise change the plan."
        const val NO_PLAN =
            "You have acted several times without a plan. If the request needs more than one or two more " +
                "actions, reply with plan now."
        const val REPEATING =
            "You have made the same call three times in a row and the screen did not get you further. " +
                "Do not repeat it: try another way, or reply with plan to change the plan."
        const val PLAN_FIRST =
            "Nothing was done: every task starts with a plan. Read the screen if you need to, then reply with " +
                "plan (1 to 7 short steps, each saying what will show it is done)."
        const val CHECKPOINT =
            "Checkpoint: compare the screen with your plan. Is the current step done? Write down with note " +
                "anything you will need later, and change the plan if it no longer fits."
    }
}

/** The controls tapped lately: three taps on the same one within the last few is going in circles. */
internal class Pokes {
    private val recent = ArrayDeque<String>()

    /** True when the control named [label] has now been tapped three times within the last few taps. */
    fun again(label: String): Boolean {
        recent.addLast(label)
        if (recent.size > REMEMBERED) recent.removeFirst()
        val circling = recent.count { it == label } >= TIMES
        if (circling) recent.clear()
        return circling
    }

    private companion object {
        const val REMEMBERED = 8
        const val TIMES = 3
    }
}
