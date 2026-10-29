package com.danielealbano.androidremotecontrolmcp.services.agent

import com.danielealbano.androidremotecontrolmcp.services.chat.AtomicFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** How a phone task stands. */
@Serializable
enum class RunStatus {
    RUNNING,

    /** Waiting for the user to allow working in an app. */
    WAITING_APPROVAL,
    DONE,
    FAILED,

    /** The user stopped it. */
    STOPPED,

    /** A person has to continue: login, captcha, payment, or the user took over. */
    HANDED_OVER,
    ;

    val finished: Boolean get() = this != RUNNING && this != WAITING_APPROVAL
}

/** One thing the agent did. */
@Serializable
data class AgentStep(
    /** "点「搜索」" */
    val label: String,
    val ok: Boolean = true,
)

/** How a step of the task's plan stands. */
@Serializable
enum class PlanState { TODO, NOW, DONE }

/** A step of the task's plan as its record shows it; [outcome] is what it achieved once done. */
@Serializable
data class PlanItem(
    val text: String,
    val state: PlanState = PlanState.TODO,
    val outcome: String = "",
    /** Finished under a plan that was changed afterwards. */
    val earlier: Boolean = false,
)

/** A place of the task's map as its record shows it: how deep it sits, how it stands, what was noted there. */
@Serializable
data class TreeLine(
    val depth: Int,
    val name: String,
    val mark: String = "",
    val facts: List<String> = emptyList(),
)

/** A phone task from request to result: what the progress card, the notification and the history show. */
@Serializable
data class AgentRun(
    val id: String,
    /** The user's request, in their words. */
    val goal: String,
    val status: RunStatus = RunStatus.RUNNING,
    val steps: List<AgentStep> = emptyList(),
    /** What the user is told at the end: the model's summary, or why it stopped. */
    val result: String = "",
    /** Sentences the user added while it ran. */
    val notes: List<String> = emptyList(),
    /** What the agent wrote down on the way; a task that is stopped or fails still hands this over. */
    val notebook: List<String> = emptyList(),
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    /** The saved skill this run was archived as, so the same request needs no model next time. */
    val archivedAs: String = "",
    /** Where the request came from when it was not typed or said in the app: "weixin". */
    val source: String = "",
    /** The plan the agent made for the task and how far each step got. */
    val plan: List<PlanItem> = emptyList(),
    /** The task's map, for a task that searched through lists. */
    val tree: List<TreeLine> = emptyList(),
)

/** The finished tasks, newest first, kept in one file so the history survives the app. */
class AgentHistoryFile(
    private val file: File,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): List<AgentRun> {
        val raw = file.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()
        return raw
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<List<AgentRun>>(it) }.getOrNull() }
            .orEmpty()
    }

    /** Puts [run] at the top (replacing an earlier record of the same task) and trims the tail. */
    @Synchronized
    fun record(run: AgentRun): List<AgentRun> {
        val runs = (listOf(run) + load().filterNot { it.id == run.id }).take(KEEP)
        AtomicFiles.save(file, json.encodeToString(runs))
        return runs
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    companion object {
        /** A task is a few hundred bytes; this many is months of use. */
        const val KEEP = 200
    }
}

/**
 * The task in progress and the ones before it. Everything that shows a task (the card in the
 * chat, the notification, the history page) reads from here.
 */
class AgentRuns(
    private val history: AgentHistoryFile,
) {
    private val _current = MutableStateFlow<AgentRun?>(null)

    /** The task running now, or the last one that ran in this process. */
    val current: StateFlow<AgentRun?> = _current.asStateFlow()

    private val _past = MutableStateFlow(history.load())

    /** Finished tasks, newest first. */
    val past: StateFlow<List<AgentRun>> = _past.asStateFlow()

    fun begin(run: AgentRun) {
        _current.value = run
    }

    /** Applies [change] to the task [id] if it is still the current one and has not finished. */
    fun update(
        id: String,
        change: (AgentRun) -> AgentRun,
    ) {
        _current.update { run -> if (run != null && run.id == id && !run.status.finished) change(run) else run }
    }

    /** Ends the task [id] with [status] and [result] and writes it to the history; a second call is ignored. */
    fun finish(
        id: String,
        status: RunStatus,
        result: String,
        endedAt: Long,
    ) {
        var ended: AgentRun? = null
        _current.update { run ->
            if (run == null || run.id != id || run.status.finished) {
                run
            } else {
                run.copy(status = status, result = result, endedAt = endedAt).also { ended = it }
            }
        }
        ended?.let { _past.value = history.record(it) }
    }

    /** A task by id: the current one, or one from the history. */
    fun find(id: String): AgentRun? = _current.value?.takeIf { it.id == id } ?: _past.value.firstOrNull { it.id == id }

    fun clearHistory() {
        history.clear()
        _past.value = emptyList()
    }
}
