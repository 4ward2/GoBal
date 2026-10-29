package com.danielealbano.androidremotecontrolmcp.services.remote.hooks

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.remote.PairedComputer
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteAgent
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One thing the auto-resume did or decided, for the list the user can read. */
data class ResumeEntry(
    val at: Long,
    val computer: String,
    val agent: String,
    val kind: InterruptionKind,
    val outcome: ResumeOutcome,
    /** For [ResumeOutcome.SCHEDULED]: how long it waits before trying. */
    val waitMs: Long = 0,
)

enum class ResumeOutcome { SCHEDULED, RESUMED, GAVE_UP, USER_TOOK_OVER, NOT_SENT }

/**
 * 「中断了自动续跑」: when an agent on a bound computer stops because of a usage limit, a busy
 * model, rate limiting or a crash, it is told to carry on once that has passed — the same thing
 * the computer-side quota_guard did, now seen and switched from the phone. Off unless the user
 * turns it on. It never answers for the user: if the user said something to the agent meanwhile,
 * or the agent is already running again, it does nothing.
 */
@Singleton
class AutoResume
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val computers: RemoteComputers,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        private val turns = TurnEnds()
        private val tries = TryCounter()
        private val waiting = HashMap<String, Job>()
        private val entries = MutableStateFlow<List<ResumeEntry>>(emptyList())

        /** What it did lately, newest first. */
        val log: StateFlow<List<ResumeEntry>> = entries.asStateFlow()

        val enabled: Flow<Boolean> = dataStore.data.map { it[KEY] ?: false }

        suspend fun setEnabled(on: Boolean) {
            dataStore.edit { it[KEY] = on }
            if (!on) cancelAll()
        }

        @Synchronized
        private fun cancelAll() {
            waiting.values.forEach(Job::cancel)
            waiting.clear()
        }

        /** Every event from every bound computer; only the end of a turn matters here. */
        fun took(
            computer: PairedComputer,
            event: RemoteEvent,
        ) {
            val agent = (event as? RemoteEvent.AgentChanged)?.agent ?: return
            if (turns.ended(computer.id + "/" + agent.id, agent)) scope.launch { ended(computer, agent) }
        }

        private suspend fun ended(
            computer: PairedComputer,
            agent: RemoteAgent,
        ) {
            val text = if (enabled.first()) agent.lastError.ifBlank { errorReply(computer.id, agent.id) } else ""
            val interruption = Interruptions.classify(text) ?: return
            val key = computer.id + "/" + agent.id
            val wait = ResumePacing.waitMs(interruption, tries.recent(key))
            val note = Note(computer.name, agent.title.ifBlank { agent.provider }, interruption.kind)
            if (wait == null) return record(note, ResumeOutcome.GAVE_UP)
            record(note, ResumeOutcome.SCHEDULED, wait)
            schedule(key, wait) { resume(computer.id, agent, note) }
        }

        @Synchronized
        private fun schedule(
            key: String,
            wait: Long,
            block: suspend () -> Unit,
        ) {
            waiting.remove(key)?.cancel()
            waiting[key] =
                scope.launch {
                    delay(wait)
                    block()
                }
        }

        private suspend fun resume(
            computerId: String,
            before: RemoteAgent,
            note: Note,
        ) {
            val session = computers.session(computerId) ?: return record(note, ResumeOutcome.NOT_SENT)
            val now = session.agents.value.firstOrNull { it.id == before.id }
            val takenOver = now == null || now.archived || now.status == RUNNING
            if (takenOver || now.lastUserMessageAt > before.lastUserMessageAt) {
                return record(note, ResumeOutcome.USER_TOOK_OVER)
            }
            val sent = session.send(before.id, RESUME_TEXT.format(REASONS.getValue(note.kind))).isSuccess
            if (sent) tries.add(computerId + "/" + before.id)
            record(note, if (sent) ResumeOutcome.RESUMED else ResumeOutcome.NOT_SENT)
        }

        /** Claude Code puts usage-limit errors in the reply itself; only a short, error-like reply counts. */
        private suspend fun errorReply(
            computerId: String,
            agentId: String,
        ): String {
            val items =
                computers
                    .session(computerId)
                    ?.history(agentId)
                    ?.getOrNull()
                    .orEmpty()
            return ErrorReplies.errorLike(GoalJudge.lastReply(items))
        }

        private fun record(
            note: Note,
            outcome: ResumeOutcome,
            waitMs: Long = 0,
        ) {
            val entry = ResumeEntry(System.currentTimeMillis(), note.computer, note.agent, note.kind, outcome, waitMs)
            entries.update { (listOf(entry) + it).take(LOG_SIZE) }
        }

        private data class Note(
            val computer: String,
            val agent: String,
            val kind: InterruptionKind,
        )

        private companion object {
            val KEY = booleanPreferencesKey("remote_auto_resume")
            const val RUNNING = "running"
            const val LOG_SIZE = 50
            const val RESUME_TEXT = "[自动续跑] 上一轮因%s中断了，请从中断的地方接着做。"
            val REASONS =
                mapOf(
                    InterruptionKind.LIMIT to "用量额度到了",
                    InterruptionKind.BUSY to "模型繁忙",
                    InterruptionKind.RATE to "请求太频繁被限流",
                    InterruptionKind.CRASH to "程序意外退出",
                )
        }
    }

/** How many times each agent was told to carry on in the last hour. */
private class TryCounter {
    private val times = HashMap<String, MutableList<Long>>()

    @Synchronized
    fun recent(key: String): Int {
        val since = System.currentTimeMillis() - HOUR_MS
        return times[key]?.apply { removeAll { it < since } }?.size ?: 0
    }

    @Synchronized
    fun add(key: String) {
        times.getOrPut(key) { mutableListOf() } += System.currentTimeMillis()
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
