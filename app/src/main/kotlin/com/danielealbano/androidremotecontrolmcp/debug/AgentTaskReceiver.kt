package com.danielealbano.androidremotecontrolmcp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRun
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRunner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lets someone at the phone with `adb` give the in-app agent a task without typing it on the
 * phone, and follow it in logcat. It is the same task the chat would start: the same model, the
 * same permission prompts on the phone's screen, the same guards. The manifest demands
 * `android.permission.DUMP` of the sender, which only the shell and the system hold.
 *
 * ```
 * adb shell am broadcast -a com.danielealbano.androidremotecontrolmcp.AGENT_RUN \
 *   -n <app-id>/com.danielealbano.androidremotecontrolmcp.debug.AgentTaskReceiver --es b64 <base64 of the request>
 * adb shell am broadcast -a com.danielealbano.androidremotecontrolmcp.AGENT_STOP -n <same component>
 * ```
 *
 * Progress is written to logcat under `MCP:AgentTask`.
 */
@AndroidEntryPoint
class AgentTaskReceiver : BroadcastReceiver() {
    @Inject
    lateinit var runner: AgentRunner

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_STOP -> {
                runner.stop()
                Log.i(TAG, "stop requested")
            }

            ACTION_RUN -> {
                run(request(intent))
            }

            else -> {
                Log.i(TAG, "unknown action")
            }
        }
    }

    /** The request, from `text` or (for anything the shell would mangle) from `b64`. */
    private fun request(intent: Intent): String {
        val encoded = intent.getStringExtra(EXTRA_B64).orEmpty()
        val decoded = runCatching { String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("")
        return decoded.ifBlank { intent.getStringExtra(EXTRA_TEXT).orEmpty() }.trim()
    }

    private fun run(goal: String) {
        val id = if (goal.isEmpty()) null else runner.start(goal)
        if (id == null) {
            Log.i(TAG, if (goal.isEmpty()) "no request given" else "busy: another task is running")
            return
        }
        Log.i(TAG, "started $id")
        scope.launch { follow(id) }
    }

    /** Logs each new step of the task [id], then how it ended. */
    private suspend fun follow(id: String) {
        var logged = 0
        runner.runs.current
            .transformWhile { run ->
                emit(run)
                run?.id == id && !run.status.finished
            }.collect { run ->
                if (run?.id == id) logged = report(run, logged)
            }
        Log.i(TAG, "-- end $id --")
    }

    /** Logs the steps of [run] after the first [logged] and, once over, its result; returns the new count. */
    private fun report(
        run: AgentRun,
        logged: Int,
    ): Int {
        run.steps.drop(logged).forEach { Log.i(TAG, "step ${if (it.ok) "ok  " else "FAIL"} ${it.label}") }
        if (run.status.finished) {
            Log.i(TAG, "ended ${run.status} after ${run.steps.size} steps")
            run.result.chunked(LINE_CHARS).forEach { Log.i(TAG, "result $it") }
        }
        return run.steps.size
    }

    companion object {
        const val ACTION_RUN = "com.danielealbano.androidremotecontrolmcp.AGENT_RUN"
        const val ACTION_STOP = "com.danielealbano.androidremotecontrolmcp.AGENT_STOP"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_B64 = "b64"
        private const val TAG = "MCP:AgentTask"
        private const val LINE_CHARS = 900

        /** Outlives the receiver, which is gone as soon as onReceive returns. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
