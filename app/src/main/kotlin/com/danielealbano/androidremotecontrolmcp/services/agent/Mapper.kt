package com.danielealbano.androidremotecontrolmcp.services.agent

import kotlinx.serialization.json.JsonObject

/** The place a reply says the phone is at (its top-level "at"), or nothing when it does not say. */
internal fun placeIn(reply: String): String = AgentProtocol.firstObject(reply)?.text("at").orEmpty()

/**
 * Keeps the [TaskTree] in step with the phone: notes where the model says it is, and takes the
 * phone back to a place it has been by replaying the recorded way there (the same replay a saved
 * skill uses, so no model is asked and no screen is walked back through by hand). What is
 * written into the tree, and what the tree says about the search, is the [search]'s part.
 */
internal class Mapper(
    private val tools: AgentTools,
    private val events: AgentEvents,
) {
    val tree = TaskTree()
    val search = SearchKeeper(tree, events)
    private val jumps = Jumps(tools, tree)

    /**
     * The places the phone was taken to, in order. Each entry is a screen that really changed (a
     * new place is only taken when the screen did), so going back to a place means going back
     * over the screens after it.
     */
    private val trail = mutableListOf<String>()

    /** The words on the screen when the model last said where the phone is; "" when not known. */
    private var screenThere = ""
    private var pending: String? = null

    /** The model named a place this turn that the screen does not show. */
    var doubted = false
        private set

    /**
     * The app was started over: the recorded way starts again, so no place can be jumped to until
     * revisited. [why], when given, is what the model is told before its next turn.
     */
    fun restarted(why: String? = null) {
        if (why != null) pending = why
        tree.forgetWays()
        jumps.forget()
        trail.clear()
    }

    /**
     * The model says the phone is at [place], with [screen] (the words on it) in front of it; a
     * place seen for the first time is put on the map. A new place is not taken on the model's
     * word while the screen is still the one it last called by another name: nothing was opened,
     * so what it would note there would be made up.
     */
    suspend fun at(
        place: String,
        screen: String,
    ) {
        val path = Paths.clean(place)
        doubted = false
        val unmoved = screenThere.isNotEmpty() && screen == screenThere && tree.here.isNotEmpty()
        if (path.isNotEmpty() && path != tree.here && unmoved) {
            pending = "The screen has not changed since you were at \"${tree.here}\", so the map keeps you there. " +
                "Name a new place only once the screen shows it, and note only what the screen shows."
            doubted = true
            events.step(AgentStep("还在「${tree.here.substringAfterLast(Paths.SEP)}」", ok = false))
            return
        }
        if (path.isNotEmpty()) screenThere = screen
        if (path.isEmpty() || path == tree.here) return
        val known = tree.views().firstOrNull { it.path == path }
        tree.arrive(path, if (known?.reachable == true) 0 else recorded())
        trail += path
        if (known?.visited != true) {
            events.step(AgentStep("到了「${path.take(LABEL_CHARS)}」"))
            search.arrived(path)
        }
    }

    /** Takes the phone to [place]; returns what the model is told. */
    suspend fun goto(place: String): String {
        val path = tree.find(place) ?: Paths.clean(place)
        val since = trail.lastIndexOf(path).let { if (it < 0) 0 else trail.lastIndex - it }
        val landing = jumps.to(path, since)
        if (landing.landed) {
            // What lay beyond the place is behind the phone now.
            val kept = trail.lastIndexOf(path)
            if (kept < 0) trail.clear() else trail.subList(kept + 1, trail.size).clear()
            if (kept < 0) trail += path
        }
        events.step(AgentStep("去「${path.take(LABEL_CHARS)}」", ok = landing.landed))
        // Where the jump landed is read afresh; the screen it shows is not compared with the old one.
        if (landing.landed) screenThere = ""
        return when (landing) {
            Landing.WALKED -> {
                "The phone went back and should be at \"$path\". This is the screen; if it is not " +
                    "that place, call goto for it again and it will be reached from the app's first screen."
            }

            Landing.REPLAYED -> {
                "You are back at \"$path\". This is the screen:"
            }

            Landing.BACKED_OUT -> {
                "The jump could not be made, so the phone went back ${jumps.levels} screen(s) and should " +
                    "be at \"$path\". This is the screen; if it is not that place, find your way there."
            }

            Landing.UNKNOWN_PLACE -> {
                val known = tree.views().filter { it.reachable }.joinToString("; ") { it.path }
                "You have not been to \"$path\" in this task, so there is no recorded way there. " +
                    "Places you can jump to: ${known.ifEmpty { "none yet" }}."
            }

            Landing.FAILED -> {
                "The jump did not work: " + jumps.failure.take(REASON_CHARS)
            }
        }
    }

    /** The search's progress and the map for the model, or nothing while no place has been named. */
    fun render(): String {
        val places = tree.views()
        if (places.isEmpty()) return ""
        return SearchProgress.render(places) + "\n\n" + MAP_TITLE + "\n" + TreeDrawing.draw(places)
    }

    /** What the harness has to say about the map before the model's next turn; handed out once. */
    fun takeHint(): String? {
        val said = listOfNotNull(pending, search.takeHint()).joinToString("\n\n")
        pending = null
        return said.ifEmpty { null }
    }

    /** [summary] for the user, with what was left unlooked at when the task stopped short of its map. */
    fun honest(
        summary: String,
        ok: Boolean,
    ): String = if (ok) summary + SearchProgress.leftOpen(tree.views()) else summary

    private suspend fun recorded(): Int {
        val status = tools.call(TASK_STATUS, JsonObject(emptyMap())).text
        return RECORDED
            .find(status)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: 0
    }

    companion object {
        const val MAP_TITLE =
            "Map (what you noted sits under the place it belongs to; goto jumps to any place you have been):"
        private const val TASK_STATUS = "task_status"
        private const val LABEL_CHARS = 40
        private const val REASON_CHARS = 300
        private val RECORDED = Regex("steps: (\\d+)")
    }
}
