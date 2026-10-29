package com.danielealbano.androidremotecontrolmcp.services.remote.hooks

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.agent.ModelTrouble
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelGateway
import com.danielealbano.androidremotecontrolmcp.services.remote.AgentSession
import com.danielealbano.androidremotecontrolmcp.services.remote.PairedComputer
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteAgent
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the user asks for when setting a goal. */
data class GoalRequest(
    val goal: String,
    val criteria: String,
    val maxRounds: Int,
    val hours: Int,
)

/**
 * 「目标模式」: an agent on a computer works until a goal is met. Each time it stops a round, the
 * phone's own model checks its reply against the user's criteria; if they are not met, the agent is
 * told what is still missing and carries on. It stops when the goal is met, when the rounds or the
 * time run out, or when it is stopped. A round that ended on a usage limit, a busy model or a crash
 * is not checked: it waits for the agent to carry on (by the auto-resume, or by the user).
 */
@Singleton
class GoalRunner
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
        private val computers: RemoteComputers,
        private val gateway: ModelGateway,
        private val settings: AssistSettingsRepository,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        private val store = GoalStore(File(context.filesDir, FILE))
        private val turns = TurnEnds()
        private val runs = MutableStateFlow(store.read())

        /** Every goal kept, active ones and the last finished ones. */
        val goals: StateFlow<List<GoalRun>> = runs.asStateFlow()

        /** Sets [request] for [agent], replacing any goal it already had, and tells the agent. */
        suspend fun start(
            computer: PairedComputer,
            agent: RemoteAgent,
            request: GoalRequest,
        ): Result<Unit> {
            val session = computers.session(computer.id)
            val problem =
                when {
                    request.goal.isBlank() || request.criteria.isBlank() -> NEEDS_BOTH
                    session == null -> OFFLINE
                    agent.status == RUNNING -> AGENT_BUSY
                    else -> null
                }
            if (problem != null || session == null) return Result.failure(IllegalStateException(problem))
            val now = System.currentTimeMillis()
            val run =
                GoalRun(
                    UUID.randomUUID().toString(),
                    computer.id,
                    computer.name,
                    agent.id,
                    agent.title.ifBlank { agent.provider },
                    request.goal.trim(),
                    request.criteria.trim(),
                    request.maxRounds.coerceIn(1, MAX_ROUNDS),
                    now + request.hours.coerceIn(1, MAX_HOURS) * HOUR_MS,
                    now,
                    now,
                )
            change { all -> all.map { it.replacedBy(run) } + run }
            val sent = session.send(agent.id, GoalJudge.kickoff(run.goal, run.criteria))
            if (sent.isFailure) set(run.id) { it.copy(state = GoalState.FAILED, note = OFFLINE) }
            return sent
        }

        fun stop(id: String) = set(id) { it.copy(state = GoalState.STOPPED, note = "") }

        /** Tells an agent whose goal paused (interrupted, or the check failed) to carry on. */
        suspend fun nudge(id: String): Result<Unit> {
            val run = runs.value.firstOrNull { it.id == id }
            val session = run?.let { computers.session(it.computerId) }
            if (run == null || session == null) return Result.failure(IllegalStateException(OFFLINE))
            set(id) { it.copy(state = GoalState.WORKING) }
            val missing = run.missing.ifBlank { PAUSED_BEFORE }
            return carryOn(run.copy(state = GoalState.WORKING), run.round, missing, session)
        }

        /** Every event from every bound computer; only the end of a round of a goal's agent matters. */
        fun took(
            computer: PairedComputer,
            event: RemoteEvent,
        ) {
            val agent = (event as? RemoteEvent.AgentChanged)?.agent ?: return
            val ended = turns.ended(computer.id + "/" + agent.id, agent)
            val run = runs.value.firstOrNull { it.belongsTo(computer.id, agent.id) && it.state.active }
            if (run?.state == GoalState.INTERRUPTED && agent.status == RUNNING) {
                set(run.id) { it.copy(state = GoalState.WORKING, note = "") }
            }
            if (ended && run != null && run.state != GoalState.JUDGING) scope.launch { roundEnded(run.id, agent) }
        }

        private suspend fun roundEnded(
            id: String,
            agent: RemoteAgent,
        ) {
            val run = runs.value.firstOrNull { it.id == id }?.takeIf { it.state.active } ?: return
            val session = computers.session(run.computerId)
            val reply =
                session
                    ?.history(agent.id)
                    ?.getOrNull()
                    ?.let(GoalJudge::lastReply)
                    .orEmpty()
            val interrupted = Interruptions.classify(agent.lastError.ifBlank { ErrorReplies.errorLike(reply) })
            when {
                session == null -> set(id) { it.copy(state = GoalState.INTERRUPTED, note = OFFLINE) }
                interrupted != null -> set(id) { it.copy(state = GoalState.INTERRUPTED, note = PAUSED) }
                System.currentTimeMillis() > run.deadline -> set(id) { it.copy(state = GoalState.OUT_OF_TIME) }
                else -> check(run, reply, session)
            }
        }

        private suspend fun check(
            run: GoalRun,
            reply: String,
            session: AgentSession,
        ) {
            set(run.id) { it.copy(state = GoalState.JUDGING, note = "") }
            val verdict = judge(run, reply)
            val found = verdict.getOrNull()
            when {
                found == null -> set(run.id) { it.copy(state = GoalState.FAILED, note = verdict.problem()) }
                found.done -> set(run.id) { it.copy(state = GoalState.DONE, missing = "") }
                run.round >= run.maxRounds -> set(run.id) { it.out(found.missing) }
                else -> carryOn(run, run.round + 1, found.missing, session)
            }
        }

        /** The phone's model on [reply]; asked twice when the first answer is not the JSON asked for. */
        private suspend fun judge(
            run: GoalRun,
            reply: String,
        ): Result<Verdict> {
            val messages = GoalJudge.messages(run, reply)
            val choice = settings.assistantModel.first()
            var problem = JUDGE_UNREADABLE
            var found: Verdict? = null
            var tries = 0
            while (found == null && tries < JUDGE_TRIES && problem == JUDGE_UNREADABLE) {
                tries++
                val answer = gateway.chat(messages, choice)
                if (answer.ok) {
                    found = GoalJudge.verdict(answer.content)
                } else {
                    problem = JUDGE_ERROR + ModelTrouble.plain(answer.error)
                }
            }
            return found?.let { Result.success(it) } ?: Result.failure(IllegalStateException(problem))
        }

        private suspend fun carryOn(
            run: GoalRun,
            round: Int,
            missing: String,
            session: AgentSession,
        ): Result<Unit> {
            val still = set(run.id) { it.copy(state = GoalState.WORKING, round = round, missing = missing, note = "") }
            if (!still) return Result.failure(IllegalStateException(STOPPED))
            val sent = session.send(run.agentId, GoalJudge.followUp(round, missing))
            if (sent.isFailure) set(run.id) { it.copy(state = GoalState.INTERRUPTED, note = OFFLINE) }
            return sent
        }

        /** Changes the goal [id] while it is active; false when it is not (stopped meanwhile, say). */
        private fun set(
            id: String,
            edit: (GoalRun) -> GoalRun,
        ): Boolean {
            var applied = false
            change { all ->
                all.map { run ->
                    if (run.id != id || !run.state.active) return@map run
                    applied = true
                    edit(run).copy(updatedAt = System.currentTimeMillis())
                }
            }
            return applied
        }

        @Synchronized
        private fun change(edit: (List<GoalRun>) -> List<GoalRun>) {
            runs.value = edit(runs.value)
            store.write(runs.value)
        }

        private companion object {
            const val FILE = "remote/goals.json"
            const val RUNNING = "running"
            const val MAX_ROUNDS = 50
            const val MAX_HOURS = 48
            const val HOUR_MS = 3_600_000L
            const val JUDGE_TRIES = 2
            const val NEEDS_BOTH = "目标和达成标准都要写"
            const val OFFLINE = "电脑不在线，没发出去"
            const val AGENT_BUSY = "代理正在干活，等它停下再设目标"
            const val PAUSED = "这一轮中断了（额度、繁忙或程序退出），等它接着做"
            const val PAUSED_BEFORE = "上一轮中断了"
            const val STOPPED = "目标已经停了"
            const val JUDGE_ERROR = "验收用的模型出错："
            const val JUDGE_UNREADABLE = "验收用的模型没按要求回答"
        }
    }

private fun GoalRun.belongsTo(
    computer: String,
    agent: String,
): Boolean = computerId == computer && agentId == agent

private fun GoalRun.out(missing: String): GoalRun = copy(state = GoalState.OUT_OF_ROUNDS, missing = missing)

private fun Result<*>.problem(): String = exceptionOrNull()?.message.orEmpty()

/** An agent's earlier goal gives way to a new one. */
private fun GoalRun.replacedBy(next: GoalRun): GoalRun =
    if (computerId == next.computerId && agentId == next.agentId && state.active) {
        copy(state = GoalState.STOPPED, note = "换了新目标", updatedAt = next.startedAt)
    } else {
        this
    }

/** Which agents are mid-round, to tell the end of a round from any other update. */
internal class TurnEnds {
    private val running = HashSet<String>()

    /** True when [agent] was running and now is not. */
    @Synchronized
    fun ended(
        key: String,
        agent: RemoteAgent,
    ): Boolean {
        val was = key in running
        if (agent.status == "running") running += key else running -= key
        return was && agent.status != "running" && !agent.archived
    }
}
