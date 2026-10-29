package com.danielealbano.androidremotecontrolmcp.services.agent

/**
 * Keeps a lookup honest against its own map: what the model sees goes into the tree at the place
 * it belongs to, the end of a list is noticed from the screen rather than taken on the model's
 * word, and two of the usual ways a search settles too early are caught. Choosing "the cheapest"
 * from the part of a list seen so far is pointed out once; saying the task is finished while
 * places on the map are still open is sent back once, and if the model stops anyway the user is
 * told what was not looked at.
 */
internal class SearchKeeper(
    private val tree: TaskTree,
    private val events: AgentEvents,
) {
    private val warnedLists = mutableSetOf<String>()
    private var pending: String? = null
    private var sentBack = false

    /** True when [move] belongs in the map rather than in the plain notes. */
    fun keeps(move: AgentMove.Note): Boolean {
        val placed = move.about.isNotBlank() || move.items.isNotEmpty()
        return placed || tree.here.isNotEmpty()
    }

    /** The model marks places to open under a place; returns what it is told. */
    fun explore(move: AgentMove.Explore): String {
        val under = Paths.clean(move.under).ifEmpty { tree.here }
        val added = tree.plan(under, move.names)
        if (added.isEmpty()) return "Nothing was added: give explore a list of names."
        events.step(AgentStep("待看 ${added.size} 处：" + move.names.joinToString("、").take(LABEL_CHARS)))
        val told = "Added to the map. Open them in the order you like; after each, come back with goto."
        return told + warning(under).orEmpty()
    }

    /** The model writes down what it sees; returns what it is told. */
    fun note(move: AgentMove.Note): String {
        val at = if (move.about.isBlank()) tree.here else tree.find(move.about) ?: Paths.join(tree.here, move.about)
        val said = mutableListOf<String>()
        var full = false
        if (move.text.isNotBlank()) {
            if (tree.write(at, move.text)) {
                events.step(
                    AgentStep(
                        "记下：" +
                            move.text
                                .trim()
                                .replace('\n', ' ')
                                .take(LABEL_CHARS),
                    ),
                )
                events.noted("[$at] ${move.text.trim()}")
                said += "Noted at \"$at\"."
            } else {
                full = at.isNotEmpty()
            }
        }
        if (move.items.isNotEmpty()) {
            val kept = items(at, move.items)
            full = full || kept < move.items.size
            if (kept > 0) said += "$kept item(s) noted under \"$at\"."
        }
        if (full) said += "Some of it was not kept: the notes are full. Finish with what you have."
        return said.joinToString(" ").ifEmpty {
            "Nothing was noted: give text (and say where you are with \"at\"), or items."
        }
    }

    private fun items(
        under: String,
        items: Map<String, String>,
    ): Int {
        var kept = 0
        items.forEach { (name, what) ->
            val place = Paths.join(under, name)
            if (tree.write(place, what.ifBlank { name }, item = true)) {
                kept++
                events.noted("[$place] $what")
            }
        }
        if (kept > 0) events.step(AgentStep("记下 $kept 样：" + items.keys.joinToString("、").take(LABEL_CHARS)))
        return kept
    }

    /** The model settles a place it needs nothing more from; returns what it is told. */
    fun close(move: AgentMove.Close): String {
        val path = tree.find(move.at.ifBlank { tree.here })
        val place = tree.views().firstOrNull { it.path == path }
        val reason = move.reason.trim()
        return when {
            place == null -> {
                "There is no place \"${move.at}\" on the map. Use a path from the map."
            }

            reason.isEmpty() -> {
                "Nothing was closed: say in reason why \"${place.path}\" needs nothing more."
            }

            else -> {
                tree.settle(place.path, reason)
                val verb = if (place.visited) "看完" else "不看"
                events.step(AgentStep("$verb「${place.name}」：${reason.take(LABEL_CHARS)}"))
                "Closed \"${place.path}\"."
            }
        }
    }

    /**
     * The phone now shows [after] ([before] was the screen until the last action, [scrolled] when
     * that action scrolled the list on). The list the phone is at is taken as seen to its end when
     * an end-of-list line is on the screen, or a scroll changed nothing. Returns what the model is
     * told about it, or "".
     */
    fun sawScreen(
        before: ScreenText,
        after: ScreenText,
        scrolled: Boolean,
    ): String {
        val place = tree.views().firstOrNull { it.here && it.closed == null && !it.end }
        val why = place?.let { ListEnds.why(it, before, after, scrolled) } ?: return ""
        tree.settle(place.path, reason = null)
        events.step(AgentStep("「${place.name}」到底了"))
        return "\n\nHarness: $why, so \"${place.path}\" counts as seen to its end."
    }

    /** The phone came to [path] for the first time. */
    fun arrived(path: String) {
        warning(Paths.parent(path))?.let { pending = it.trim() }
    }

    /** Said once a list: a choice made from it before it was seen whole may miss the best of it. */
    private fun warning(list: String): String? {
        val place = tree.views().firstOrNull { it.path == list }
        val choosingFromPart = place?.let { it.list && it.visited && !it.settled } == true
        if (!choosingFromPart || !warnedLists.add(list)) return null
        return "\n\nHarness: \"$list\" is not seen to its end. If you are choosing from it (cheapest, best, " +
            "all of them), the choice must be made over the whole list, not the part seen so far: finish the " +
            "list first (scroll on, then come back with goto), or close it with why the rest cannot matter."
    }

    /** What the harness has to say before the model's next turn; handed out once. */
    fun takeHint(): String? = pending.also { pending = null }

    /**
     * The model says the task is finished. The first time places on the map are still open it is
     * sent back to them (returns what it is told); after that, or when nothing is open, null.
     */
    fun beforeDone(ok: Boolean): String? {
        val open = SearchProgress.unsettled(tree.views())
        if (!ok || sentBack || open.isEmpty()) return null
        sentBack = true
        events.step(AgentStep("还有 ${open.size} 处没有结论，先不结束", ok = false))
        return "Not finished: these places on your map have no outcome yet:\n" +
            open.joinToString("\n") { "- $it" } +
            "\nOpen them, or close each with why it does not matter. If you must stop anyway, reply done " +
            "again and say in the summary what you did not look at."
    }

    private companion object {
        const val LABEL_CHARS = 24
    }
}
