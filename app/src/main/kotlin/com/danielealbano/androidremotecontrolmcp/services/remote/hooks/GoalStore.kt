package com.danielealbano.androidremotecontrolmcp.services.remote.hooks

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.chat.AtomicFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The goals, on disk at `files/remote/goals.json`: the active ones and the last few finished ones. */
class GoalStore(
    private val file: File,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): List<GoalRun> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString(GoalFile.serializer(), file.readText()).goals }
            .onFailure {
                Log.w(TAG, "goals unreadable, kept aside: ${it.javaClass.simpleName}")
                file.copyTo(File(file.parentFile, file.name + ".broken"), overwrite = true)
            }.getOrDefault(emptyList())
    }

    fun write(goals: List<GoalRun>) {
        val (active, finished) = goals.partition { it.state.active }
        val kept = active + finished.sortedByDescending { it.updatedAt }.take(FINISHED_KEPT)
        AtomicFiles.save(file, json.encodeToString(GoalFile.serializer(), GoalFile(kept)))
    }

    @Serializable
    private data class GoalFile(
        val goals: List<GoalRun>,
    )

    private companion object {
        const val TAG = "MCP:Goals"
        const val FINISHED_KEPT = 20
    }
}

/** Whether an agent's reply is really an error it printed (Claude Code prints usage limits that way). */
internal object ErrorReplies {
    private const val MAX = 600
    private val MARKS = listOf("api error", "error:", "limit reached", "后重置", "overloaded", "at capacity")

    fun errorLike(reply: String): String {
        val lower = reply.lowercase()
        return if (reply.length < MAX && MARKS.any { it in lower }) reply else ""
    }
}
