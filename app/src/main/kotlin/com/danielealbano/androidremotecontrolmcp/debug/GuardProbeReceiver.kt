package com.danielealbano.androidremotecontrolmcp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.agent.GuardProbe
import com.danielealbano.androidremotecontrolmcp.services.agent.InAppTools
import com.danielealbano.androidremotecontrolmcp.services.agent.ProbeScript
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Lets someone at the phone with `adb` see what the agent's guards make of the screen in front.
 * The manifest demands `android.permission.DUMP` of the sender, which only the shell and the
 * system hold: no other app can call it.
 *
 * ```
 * # Read-only: what would be stopped on this screen. Presses nothing.
 * adb shell am broadcast -a com.danielealbano.androidremotecontrolmcp.GUARD_JUDGE \
 *   -n <app-id>/com.danielealbano.androidremotecontrolmcp.debug.GuardProbeReceiver --es text "提交订单"
 *
 * # The real task loop with a script for a model, on the guard test app only.
 * adb shell am broadcast -a com.danielealbano.androidremotecontrolmcp.GUARD_LOOP \
 *   -n <app-id>/com.danielealbano.androidremotecontrolmcp.debug.GuardProbeReceiver --es text "提交订单" --ei up 0
 * ```
 *
 * The answer is written to logcat under `MCP:GuardProbe`.
 */
@AndroidEntryPoint
class GuardProbeReceiver : BroadcastReceiver() {
    @Inject
    lateinit var tools: InAppTools

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val up = intent.getIntExtra(EXTRA_UP, 0)
        val action = intent.action.orEmpty()
        val pending = goAsync()
        scope.launch {
            try {
                val lines = withTimeoutOrNull(TIMEOUT_MS) { answer(action, text, up) } ?: listOf("timed out")
                lines.forEach { Log.i(TAG, it) }
                Log.i(TAG, "-- end --")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun answer(
        action: String,
        text: String,
        up: Int,
    ): List<String> {
        tools.prepare()
        val probe = GuardProbe(tools)
        return when (action) {
            ACTION_JUDGE -> {
                listOf("judge \"$text\" (nothing is pressed)") + probe.judge(text)
            }

            ACTION_LOOP -> {
                val outcome = probe.loop(ProbeScript(text, up), TEST_APP)
                listOf("loop \"$text\" up=$up", "ended: ${outcome.status}", "said: ${outcome.result}")
            }

            else -> {
                listOf("unknown action")
            }
        }
    }

    companion object {
        const val ACTION_JUDGE = "com.danielealbano.androidremotecontrolmcp.GUARD_JUDGE"
        const val ACTION_LOOP = "com.danielealbano.androidremotecontrolmcp.GUARD_LOOP"

        /** The only app the scripted loop may press anything in: a page of harmless buttons. */
        const val TEST_APP = "com.phonemcp.guardtest"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_UP = "up"
        private const val TAG = "MCP:GuardProbe"
        private const val TIMEOUT_MS = 25_000L
    }
}
