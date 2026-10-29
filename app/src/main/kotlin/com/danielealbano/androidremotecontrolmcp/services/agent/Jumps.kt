package com.danielealbano.androidremotecontrolmcp.services.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How a jump back to a place of the map went. */
internal enum class Landing {
    /** The recorded way was replayed from the app's first screen. */
    REPLAYED,

    /** A short way back was walked with the back key, which is quick but not certain. */
    WALKED,

    /** The way could not be replayed; the back key was pressed once for each level up the map. */
    BACKED_OUT,

    /** The task has not been to the place, and it is not above where the phone is. */
    UNKNOWN_PLACE,

    FAILED,
    ;

    val landed: Boolean get() = this == REPLAYED || this == WALKED || this == BACKED_OUT
}

/**
 * Takes the phone back to a place of the [tree] with `task_goto`. When the caller knows how many
 * screens were opened since the place (back to the list after reading an item), that many
 * presses of the back key do it, as a person would; otherwise the recorded way is replayed. If
 * `task_goto` cannot help at all, the known number of presses is made directly.
 */
internal class Jumps(
    private val tools: AgentTools,
    private val tree: TaskTree,
) {
    private var walkedBackTo = ""

    /** What the phone said when the last jump failed. */
    var failure = ""
        private set

    /** How many screens the last [Landing.BACKED_OUT] went back. */
    var levels = 0
        private set

    /** The app was started over: no short walk back is pending a check. */
    fun forget() {
        walkedBackTo = ""
    }

    /** Goes to [path], which [back] screens were opened since (0 when that is not known). */
    suspend fun to(
        path: String,
        back: Int,
    ): Landing {
        val steps = tree.stepsTo(path)
        val known = back.takeIf { it in 1..MOST_LEVELS_BACK } ?: 0
        val reply = steps?.let { tools.call(TASK_GOTO, jumpArgs(it, path, known)) }
        val went = worked(reply)
        levels = known
        val landing =
            when {
                went && reply?.text?.contains(WENT_BACK) == true -> Landing.WALKED
                went -> Landing.REPLAYED
                levels > 0 && backOut(levels) -> Landing.BACKED_OUT
                steps == null -> Landing.UNKNOWN_PLACE
                else -> Landing.FAILED
            }
        failure = reply?.text.orEmpty()
        walkedBackTo = if (landing == Landing.WALKED) path else ""
        if (landing.landed) tree.movedTo(path, steps ?: Int.MAX_VALUE)
        return landing
    }

    private fun jumpArgs(
        steps: Int,
        path: String,
        back: Int,
    ): JsonObject =
        buildJsonObject {
            put("step", steps)
            // A jump made with the back key that did not land right is asked for again: then it is replayed.
            val again = path == walkedBackTo
            put("replay", again)
            if (back > 0 && !again) put("back", back)
        }

    private fun worked(reply: ToolReply?): Boolean = reply != null && !reply.isError && reply.text.startsWith(WENT)

    /** Presses the back key [times] times; false as soon as one press does not work. */
    private suspend fun backOut(times: Int): Boolean {
        val none = JsonObject(emptyMap())
        return (1..times).all { !tools.call(PRESS_BACK, none).isError }
    }

    companion object {
        const val TASK_GOTO = "task_goto"
        const val WENT = "status: done"
        private const val WENT_BACK = "went back"
        private const val PRESS_BACK = "press_back"
        private const val MOST_LEVELS_BACK = 3
    }
}
