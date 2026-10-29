package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Terminal info from list or create response (messages.ts TerminalInfoSchema line 6245). */
data class TerminalInfo(
    val id: String,
    val name: String,
    val cwd: String,
    val title: String? = null,
)

/** Terminal screen state from capture response (messages.ts lines 6356-6369). */
data class TerminalScreenState(
    val lines: List<String>,
    val totalLines: Int,
)

/** Terminal client message for input/resize/mouse. */
sealed class TerminalClientMessage {
    data class Input(
        val data: String,
    ) : TerminalClientMessage()

    data class Resize(
        val rows: Int,
        val cols: Int,
        val intent: String? = null,
    ) : TerminalClientMessage()
}

/**
 * Paseo terminals client. Parses responses and manages terminal polling.
 * Messages from messages.ts:
 * - list_terminals_request (2892)
 * - create_terminal_request (2912)
 * - terminal_input (3007)
 * - kill_terminal_request (3013)
 * - capture_terminal_request (3019)
 * - create_terminal_response (6315)
 * - kill_terminal_response (6351)
 * - capture_terminal_response (6356)
 *
 * Terminal output is streamed as BINARY websocket frames, not text.
 * To read terminal state, poll capture_terminal_request every 1 second (or after input).
 */
class PaseoTerminals(
    private val rpc: PaseoRpc,
) {
    companion object {
        private const val POLL_INTERVAL_MS = 1000L
    }

    /**
     * List terminals in a workspace.
     */
    suspend fun list(
        cwd: String? = null,
        workspaceId: String? = null,
    ): Result<List<TerminalInfo>> =
        rpc
            .call(
                "list_terminals_request",
                buildJsonObject {
                    if (cwd != null) put("cwd", cwd)
                    if (workspaceId != null) put("workspaceId", workspaceId)
                },
            ).mapCatching { payload ->
                parseTerminalList(payload)
            }

    /**
     * Create a new terminal.
     */
    suspend fun create(
        cwd: String,
        name: String? = null,
        workspaceId: String? = null,
    ): Result<TerminalInfo> =
        rpc
            .call(
                "create_terminal_request",
                buildJsonObject {
                    put("cwd", cwd)
                    if (name != null) put("name", name)
                    if (workspaceId != null) put("workspaceId", workspaceId)
                },
            ).mapCatching { payload ->
                parseCreateTerminalResponse(payload)
            }

    /**
     * Start polling terminal output. Returns a flow of screen states that polls every 1 second.
     * The flow will emit the current screen state on each poll and whenever input is sent.
     */
    fun startPolling(terminalId: String): Flow<TerminalScreenState> =
        flow {
            while (currentCoroutineContext().isActive) {
                val result = capture(terminalId)
                if (result.isSuccess) {
                    emit(result.getOrNull() ?: continue)
                }
                delay(POLL_INTERVAL_MS)
            }
        }

    /**
     * Manually trigger a capture (used after input to get immediate feedback).
     */
    suspend fun refreshScreen(terminalId: String): Result<TerminalScreenState> = capture(terminalId)

    /**
     * Capture terminal screen (read lines between start and end).
     * stripAnsi=true removes ANSI escape sequences from output.
     */
    private suspend fun capture(
        terminalId: String,
        start: Int? = null,
        end: Int? = null,
    ): Result<TerminalScreenState> =
        rpc
            .call(
                "capture_terminal_request",
                buildJsonObject {
                    put("terminalId", terminalId)
                    if (start != null) put("start", start)
                    if (end != null) put("end", end)
                    put("stripAnsi", true)
                },
            ).mapCatching { payload ->
                parseTerminalScreenState(payload)
            }

    /**
     * Send input to terminal (text or special key).
     */
    suspend fun send(
        terminalId: String,
        message: TerminalClientMessage,
    ): Result<Unit> =
        rpc
            .tell(
                "terminal_input",
                buildJsonObject {
                    put("terminalId", terminalId)
                    putJsonObject("message") {
                        when (message) {
                            is TerminalClientMessage.Input -> {
                                put("type", "input")
                                put("data", message.data)
                            }

                            is TerminalClientMessage.Resize -> {
                                put("type", "resize")
                                put("rows", message.rows)
                                put("cols", message.cols)
                                if (message.intent != null) put("intent", message.intent)
                            }
                        }
                    }
                },
            ).let { if (it) Result.success(Unit) else Result.failure(IllegalStateException("Failed to send input")) }

    /**
     * Kill a terminal.
     */
    suspend fun kill(terminalId: String): Result<Unit> =
        rpc
            .call(
                "kill_terminal_request",
                buildJsonObject { put("terminalId", terminalId) },
            ).mapCatching { payload ->
                if (payload["success"]?.jsonPrimitive?.contentOrNull?.toBoolean() == true) {
                    Unit
                } else {
                    error("Kill failed")
                }
            }

    private fun parseTerminalList(payload: JsonObject): List<TerminalInfo> {
        val terminals = payload["terminals"]?.jsonArray ?: return emptyList()
        return terminals.mapNotNull { terminalJson ->
            val obj = terminalJson.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
            val cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull ?: ""
            val title = obj["title"]?.jsonPrimitive?.contentOrNull
            TerminalInfo(id, name, cwd, title)
        }
    }

    private fun parseCreateTerminalResponse(payload: JsonObject): TerminalInfo {
        val terminal = payload["terminal"]?.jsonObject ?: error("No terminal in response")
        val id = terminal["id"]?.jsonPrimitive?.contentOrNull ?: error("Missing terminal id")
        val name = terminal["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val cwd = terminal["cwd"]?.jsonPrimitive?.contentOrNull ?: ""
        val title = terminal["title"]?.jsonPrimitive?.contentOrNull
        return TerminalInfo(id, name, cwd, title)
    }

    private fun parseTerminalScreenState(payload: JsonObject): TerminalScreenState {
        val lines = payload["lines"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val totalLines = payload["totalLines"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        return TerminalScreenState(lines, totalLines)
    }
}
