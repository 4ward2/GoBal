package com.danielealbano.androidremotecontrolmcp.services.agent

import com.danielealbano.androidremotecontrolmcp.services.skill.SkillStep

/** One place of a task's map as it reads at a moment: what the progress and the drawing are made from. */
internal data class PlaceView(
    val path: String,
    val here: Boolean = false,
    val visited: Boolean = false,
    /** The harness can take the phone back here (the recorded way to it is still good). */
    val reachable: Boolean = false,
    val wanted: Boolean = false,
    val list: Boolean = false,
    val end: Boolean = false,
    val closed: String? = null,
    val facts: List<String> = emptyList(),
) {
    val name: String get() = path.substringAfterLast(Paths.SEP)
    val depth: Int get() = path.split(Paths.SEP).size - 1
    val parent: String get() = Paths.parent(path)

    /**
     * Nothing more is owed here: it was closed with a reason; a list was seen to its end; a place
     * marked to open has something noted at it. A place only passed through owes nothing.
     */
    val settled: Boolean
        get() =
            when {
                closed != null -> true
                list -> end
                wanted -> facts.isNotEmpty()
                else -> true
            }
}

/** Paths of the map: short names joined by " > ", however the model spaced them. Pure. */
internal object Paths {
    const val SEP = " > "

    fun clean(path: String): String =
        path
            .split(">")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(SEP)

    /** [child] under [parent]; [child] may itself be a path. */
    fun join(
        parent: String,
        child: String,
    ): String = clean(if (parent.isEmpty()) child else parent + SEP + child)

    fun parent(path: String): String = path.substringBeforeLast(SEP, "")

    /** The places above [path], the top one first. */
    fun ancestors(path: String): List<String> {
        val parts = path.split(SEP)
        return (1 until parts.size).map { parts.take(it).joinToString(SEP) }
    }
}

/**
 * Where a lookup stands, read off its map. Every lookup goes through the same four stages, and
 * the model is shown each turn which one it is in and what is left in it; it chooses the order
 * within a stage, and may settle a place without opening it. Pure.
 */
internal object SearchProgress {
    enum class Stage { SCOPE, SURVEY, DIVE, ANSWER }

    private val TITLES =
        mapOf(
            Stage.SCOPE to "Find the list(s) the request is about; mark what to open with explore",
            Stage.SURVEY to "See each list to its end (scroll on) or close it with why the rest cannot matter",
            Stage.DIVE to "Open what is marked and note what is inside, or close what is not worth it",
            Stage.ANSWER to "Answer from the map with done",
        )
    private const val NAMES_SHOWN = 8

    fun stage(places: List<PlaceView>): Stage {
        val open = places.filterNot { it.settled }
        return when {
            places.none { it.list || it.wanted } -> Stage.SCOPE
            open.any { it.list && it.visited } -> Stage.SURVEY
            open.isNotEmpty() -> Stage.DIVE
            else -> Stage.ANSWER
        }
    }

    /** The stages with the one the task is in, and what is left in it; empty while nothing is mapped. */
    fun render(places: List<PlaceView>): String {
        if (places.isEmpty()) return ""
        val now = stage(places)
        return buildString {
            appendLine("Search progress (the harness keeps it from your map; you choose the order):")
            Stage.entries.forEach { stage ->
                val mark =
                    if (stage < now) {
                        "[done]"
                    } else if (stage == now) {
                        "[now]"
                    } else {
                        "[todo]"
                    }
                val left = if (stage == now) left(stage, places) else ""
                appendLine("$mark ${stage.ordinal + 1}. ${TITLES.getValue(stage)}$left")
            }
        }.trimEnd()
    }

    /** What keeps [stage] from being over, after a dash; "" when nothing is left to say. */
    private fun left(
        stage: Stage,
        places: List<PlaceView>,
    ): String {
        val open = places.filterNot { it.settled }
        val said =
            when (stage) {
                Stage.SURVEY -> {
                    open.filter { it.list && it.visited }.joinToString("; ") { list ->
                        val noted = places.count { it.parent == list.path && it.facts.isNotEmpty() }
                        "${list.path}: $noted items noted, end not seen"
                    }
                }

                Stage.DIVE -> {
                    "waiting: " + names(open)
                }

                else -> {
                    ""
                }
            }
        return if (said.isEmpty()) "" else " — $said"
    }

    private fun names(open: List<PlaceView>): String {
        val shown = open.take(NAMES_SHOWN).joinToString("; ") { it.path }
        return if (open.size > NAMES_SHOWN) "$shown; and ${open.size - NAMES_SHOWN} more" else shown
    }

    /** Each place not settled, with why, for the model. */
    fun unsettled(places: List<PlaceView>): List<String> =
        places.filterNot { it.settled }.map { place ->
            val why =
                when {
                    place.list && place.visited -> "a list not seen to its end"
                    !place.visited -> "not opened"
                    else -> "opened, nothing noted"
                }
            "${place.path} ($why)"
        }

    /** For the user, after the summary: what was left unlooked at; "" when nothing was. */
    fun leftOpen(places: List<PlaceView>): String {
        val open = places.filterNot { it.settled }
        if (open.isEmpty()) return ""
        return "\n\n（没看完的：" + open.joinToString("、") { it.name } + "）"
    }
}

/** The map drawn for the model: one place a line, indented by depth, and under it what was noted there. Pure. */
internal object TreeDrawing {
    private const val REASON_SHOWN = 40

    fun draw(places: List<PlaceView>): String =
        places.joinToString("\n") { place ->
            val indent = "  ".repeat(place.depth)
            val marks = marks(place)
            val head = indent + "- " + place.name + if (marks.isEmpty()) "" else " [${marks.joinToString(", ")}]"
            head + place.facts.joinToString("") { "\n$indent    · $it" }
        }

    private fun marks(place: PlaceView): List<String> =
        buildList {
            if (place.here) add("here")
            if (place.list) {
                add(
                    when {
                        place.end -> "list, end seen"
                        place.closed != null -> "list"
                        else -> "list, end not seen"
                    },
                )
            }
            when {
                place.closed != null -> add("closed: ${place.closed.take(REASON_SHOWN)}")
                place.here -> Unit
                place.visited -> add("opened")
                place.facts.isNotEmpty() -> add("from the list")
                place.wanted -> add("to open")
            }
        }
}

/** Whether a list was seen to its end, judged from the screen rather than from what the model says. Pure. */
internal object ListEnds {
    private val END_TEXTS = SkillStep.DEFAULT_END_TEXTS

    /**
     * Why [place] counts as seen to its end now that the phone shows [after] ([before] was the
     * screen until the last action, [scrolled] when that action moved the list on), or null when
     * it does not: an end-of-list line on the screen (for a list, or right after scrolling one),
     * or a scroll that changed nothing.
     */
    fun why(
        place: PlaceView,
        before: ScreenText,
        after: ScreenText,
        scrolled: Boolean,
    ): String? {
        val marker = END_TEXTS.firstOrNull { it in after.allText }?.takeIf { scrolled || place.list }
        return when {
            after.labels.isEmpty() -> null
            marker != null -> "the end of the list is on the screen (\"$marker\")"
            scrolled && after.allText == before.allText -> "scrolling changed nothing"
            else -> null
        }
    }
}

/** The map as the task's record shows it to the user: every place with how it stands, in their words. Pure. */
internal object TreeLines {
    private const val REASON_SHOWN = 24

    fun of(places: List<PlaceView>): List<TreeLine> =
        places.map { place ->
            TreeLine(place.depth, place.name, mark(place), place.facts)
        }

    private fun mark(place: PlaceView): String =
        listOfNotNull(
            "在这里".takeIf { place.here },
            listMark(place),
            state(place),
        ).joinToString(" · ")

    private fun listMark(place: PlaceView): String? =
        when {
            !place.list -> null
            place.end -> "列表已到底"
            place.closed != null -> "列表"
            else -> "列表没看完"
        }

    private fun state(place: PlaceView): String? {
        val reason = place.closed?.take(REASON_SHOWN)
        return when {
            reason != null -> (if (place.visited) "看完：" else "不看：") + reason
            place.here -> null
            place.visited -> "看过"
            place.facts.isNotEmpty() -> "列表里读到"
            place.wanted -> "待看"
            else -> null
        }
    }
}
