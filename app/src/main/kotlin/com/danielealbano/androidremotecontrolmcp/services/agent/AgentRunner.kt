package com.danielealbano.androidremotecontrolmcp.services.agent

import android.content.Context
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationStore
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/** What a running task needs from the app around it. */
class AgentRunnerDeps
    @Inject
    constructor(
        val tools: InAppTools,
        val gateway: ModelGateway,
        val store: ConversationStore,
        val approvals: AppApprovals,
        val state: AgentState,
    )

/** Where a task comes from, when not from the chat on screen. */
class TaskOrigin(
    /** "" for the app itself; otherwise the channel's name, kept with the task's record. */
    val source: String = "",
    /** The model to decide the steps; null for the model of the contact on screen. */
    val choice: ModelChoice? = null,
)

/**
 * Runs phone tasks in the app, one at a time, and lets the user stay in charge of the one that
 * is running: stop it, take over, or add a sentence. The task lives in the application's own
 * scope (kept alive by [AgentService]), not in a screen's, because the first thing it usually
 * does is leave the chat for another app.
 */
@Singleton
class AgentRunner
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val deps: AgentRunnerDeps,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /** The task in progress and the finished ones. */
        val runs: AgentRuns get() = deps.state.runs

        private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        private val notes = ConcurrentLinkedQueue<String>()
        private var job: Job? = null
        private var counter = 0

        /** True while a task is running or waiting for the user's permission. */
        val busy: Boolean get() = unfinished() != null

        /** The task that is running or waiting for the user, if any. */
        private fun unfinished(): AgentRun? = runs.current.value?.takeUnless { it.status.finished }

        /**
         * Starts doing [goal] and returns the task's id, or null when another task is still
         * running (the phone has one screen: tasks never overlap).
         */
        @Synchronized
        fun start(
            goal: String,
            origin: TaskOrigin = TaskOrigin(),
        ): String? {
            if (busy) return null
            counter += 1
            val id = "t${System.currentTimeMillis()}-$counter"
            notes.clear()
            deps.approvals.newTask(unattended = origin.source.isNotEmpty())
            runs.begin(AgentRun(id, goal, startedAt = System.currentTimeMillis(), source = origin.source))
            // Android may refuse a foreground service started from the background; the task still runs.
            runCatching { AgentService.start(context) }
            val previous = job
            job =
                scope.launch {
                    // A task that was just stopped is still closing its journal: this one must not
                    // open its own before that.
                    previous?.join()
                    runs.finish(id, work(id, goal, origin.choice))
                }
            return id
        }

        private fun AgentRuns.finish(
            id: String,
            outcome: AgentOutcome,
        ) = finish(id, outcome.status, withNotebook(outcome.status, outcome.result), System.currentTimeMillis())

        /** A task that did not finish still hands over what it had written down. */
        private fun withNotebook(
            status: RunStatus,
            result: String,
        ): String {
            val kept =
                runs.current.value
                    ?.notebook
                    .orEmpty()
            if (status == RunStatus.DONE || kept.isEmpty()) return result
            return result + "\n\n已经记下的：\n" + kept.joinToString("\n") { "· $it" }
        }

        private suspend fun work(
            id: String,
            goal: String,
            asked: ModelChoice?,
        ): AgentOutcome {
            val worked =
                runCatching {
                    deps.tools.prepare()
                    val choice = asked ?: deps.store.modelChoice()
                    val brain =
                        AgentBrain { messages ->
                            deps.gateway.chat(messages, choice, REPLY_TIMEOUT_S).also { reply ->
                                // Off unless someone at the phone turns it on:
                                // adb shell setprop log.tag.MCP:AgentRunner DEBUG
                                if (Log.isLoggable(TAG, Log.DEBUG)) {
                                    val sent = messages.sumOf { it.text.length }
                                    val said = reply.content.take(REPLY_LOG_CHARS)
                                    Log.d(TAG, "asked $sent chars, ok=${reply.ok}: $said")
                                }
                            }
                        }
                    PhoneAgent(deps.tools, brain, deps.approvals, events(id), planFirst = true).run(goal)
                }
            val failure = worked.exceptionOrNull()
            if (failure is CancellationException) {
                // Stopped or taken over: close the journal so the next task starts clean.
                withContext(NonCancellable) { deps.tools.call(TASK_END, buildJsonObject { put("success", false) }) }
                throw failure
            }
            if (failure != null) Log.w(TAG, "task failed: ${failure.javaClass.simpleName}")
            // The error's own words are not kept: they can quote what a service or a file said.
            return worked.getOrNull() ?: AgentOutcome(RunStatus.FAILED, "出错了，这次没有做成")
        }

        private fun events(id: String) =
            object : AgentEvents {
                override fun step(step: AgentStep) = runs.update(id) { it.copy(steps = it.steps + step) }

                override fun waitingForApproval(waiting: Boolean) =
                    runs.update(id) {
                        it.copy(status = if (waiting) RunStatus.WAITING_APPROVAL else RunStatus.RUNNING)
                    }

                override fun archived(skillName: String) = runs.update(id) { it.copy(archivedAs = skillName) }

                override fun noted(text: String) = runs.update(id) { it.copy(notebook = it.notebook + text) }

                override fun planned(plan: List<PlanItem>) = runs.update(id) { it.copy(plan = plan) }

                override fun mapped(tree: List<TreeLine>) = runs.update(id) { it.copy(tree = tree) }

                override fun takeNotes(): List<String> = generateSequence { notes.poll() }.toList()
            }

        /** The user pressed stop. */
        fun stop() = interrupt(RunStatus.STOPPED, "你停止了这次操作")

        /** The user wants to carry on by hand from where the agent is. */
        fun takeOver() = interrupt(RunStatus.HANDED_OVER, "已经交给你，接下来由你操作")

        @Synchronized
        private fun interrupt(
            status: RunStatus,
            result: String,
        ) {
            val id = unfinished()?.id ?: return
            job?.cancel()
            runs.finish(id, status, withNotebook(status, result), System.currentTimeMillis())
        }

        /**
         * Adds a sentence to the running task ("补充"): the model sees it at its next step. False
         * when nothing is running to add it to.
         */
        fun supplement(text: String): Boolean {
            val clean = text.trim()
            val id = unfinished()?.id
            if (id == null || clean.isEmpty()) return false
            notes += clean
            runs.update(id) { it.copy(notes = it.notes + clean) }
            return true
        }

        private companion object {
            /** A step's reply; the on-phone model reads a whole screen slowly. */
            const val REPLY_TIMEOUT_S = 90
            const val TASK_END = "task_end"
            const val TAG = "MCP:AgentRunner"
            const val REPLY_LOG_CHARS = 600
        }
    }
