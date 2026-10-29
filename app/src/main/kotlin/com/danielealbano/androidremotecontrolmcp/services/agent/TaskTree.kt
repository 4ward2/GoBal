package com.danielealbano.androidremotecontrolmcp.services.agent

/**
 * A task's search tree, and its memory: the places it has been to or means to open (a list, the
 * items in it, what is inside an item) and what was written down at each. Screens are not kept
 * from one turn to the next, so what the model saw survives only here, at the place it belongs
 * to. Each place also remembers how many recorded steps it took to get there, which is what lets
 * the harness take the phone back to it directly instead of the model walking back screen by
 * screen. Pure.
 */
internal class TaskTree {
    private class Place(
        /** Recorded steps when the task first arrived; null for a place it cannot be taken back to. */
        var arrivedAfter: Int?,
    ) {
        /** The task has been here, whether or not the way there is still known. */
        var visited = false

        /** Marked to be opened ([plan]): it needs an outcome before the task is finished. */
        var wanted = false

        /** Items under it were written down or marked: it is a list, to be seen to its end. */
        var list = false

        /** The end of the list was on the screen: the harness saw it, the model did not just say so. */
        var end = false

        /** Why nothing more is needed here, in the model's words. */
        var closed: String? = null
        val facts = mutableListOf<String>()
    }

    private val places = LinkedHashMap<String, Place>()
    private var factChars = 0

    /** Where the phone is, as the model last said; empty before it has said. */
    var here: String = ""
        private set

    val isEmpty: Boolean get() = places.isEmpty()

    /** The task is at [path], reached after [recorded] steps the first time it got there. */
    fun arrive(
        path: String,
        recorded: Int,
    ) {
        val place = Paths.clean(path)
        if (place.isEmpty()) return
        val known = ensure(place)
        if (known.arrivedAfter == null) known.arrivedAfter = recorded
        known.visited = true
        here = place
    }

    /** Marks [names] under [under] as places to open; [under] becomes a list. Returns their paths. */
    fun plan(
        under: String,
        names: List<String>,
    ): List<String> {
        val parent = Paths.clean(under)
        val added = names.map { Paths.join(parent, it) }.filter { it.isNotEmpty() }.distinct()
        if (added.isEmpty()) return added
        if (parent.isNotEmpty()) ensure(parent).list = true
        added.forEach { ensure(it).wanted = true }
        return added
    }

    /**
     * Keeps [text] at [path]. An [item] was read off the list [path] sits in, which makes that a
     * list. False when the text was empty or the notes are full, and nothing was kept.
     */
    fun write(
        path: String,
        text: String,
        item: Boolean = false,
    ): Boolean {
        val place = Paths.clean(path)
        val fact = text.trim().replace('\n', ' ').take(FACT_CHARS)
        val fits = place.isNotEmpty() && fact.isNotEmpty() && factChars + fact.length <= ALL_FACT_CHARS
        if (!fits) return false
        ensure(place).facts += fact
        factChars += fact.length
        val parent = Paths.parent(place)
        if (item && parent.isNotEmpty()) ensure(parent).list = true
        return true
    }

    /**
     * Nothing more is needed at [path]: for [reason], or, when it is null, because the end of the
     * list there was seen. True when that changed anything.
     */
    fun settle(
        path: String,
        reason: String?,
    ): Boolean {
        val place = places[Paths.clean(path)] ?: return false
        val before = place.end to place.closed
        if (reason == null) place.end = true else place.closed = reason.trim().take(REASON_CHARS)
        return before != (place.end to place.closed)
    }

    /** How many recorded steps lead to [path]; null when the task cannot be taken back there. */
    fun stepsTo(path: String): Int? = places[Paths.clean(path)]?.arrivedAfter

    /** The harness took the phone to [path], which lies [recorded] steps along the way: deeper ways are gone. */
    fun movedTo(
        path: String,
        recorded: Int,
    ) {
        here = Paths.clean(path)
        forgetWays(beyond = recorded)
    }

    /**
     * The recorded way no longer reaches the places arrived at after [beyond] steps (it was cut
     * back, or started over with the app): they stay on the map with what was noted there, but
     * cannot be jumped to until the task comes by again.
     */
    fun forgetWays(beyond: Int = -1) {
        places.values.forEach { place -> if ((place.arrivedAfter ?: 0) > beyond) place.arrivedAfter = null }
    }

    /** Every place, in the order it came on the map, as it reads now. */
    fun views(): List<PlaceView> =
        places.map { (path, place) ->
            PlaceView(
                path = path,
                here = path == here,
                visited = place.visited,
                reachable = place.arrivedAfter != null,
                wanted = place.wanted,
                list = place.list,
                end = place.end,
                closed = place.closed,
                facts = place.facts.toList(),
            )
        }

    /**
     * The place the model means by [name]: a path on the map, a place of that name under the
     * one the phone is at, or the only place of that name anywhere. Null when none fits.
     */
    fun find(name: String): String? {
        val wanted = Paths.clean(name)
        val underHere = Paths.join(here, wanted)
        return when {
            wanted.isEmpty() -> null
            wanted in places -> wanted
            underHere in places -> underHere
            else -> places.keys.filter { it.endsWith(Paths.SEP + wanted) }.singleOrNull()
        }
    }

    /** The place at [path], with the places above it, made when missing. */
    private fun ensure(path: String): Place {
        Paths.ancestors(path).forEach { places.getOrPut(it) { Place(arrivedAfter = null) } }
        return places.getOrPut(path) { Place(arrivedAfter = null) }
    }

    private companion object {
        const val FACT_CHARS = 1_200
        const val ALL_FACT_CHARS = 8_000
        const val REASON_CHARS = 80
    }
}
