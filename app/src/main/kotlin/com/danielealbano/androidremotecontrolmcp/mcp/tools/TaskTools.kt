package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.skill.ArchiveCut
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillCoordinator
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRunStatus
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillStep
import com.danielealbano.androidremotecontrolmcp.services.skill.TaskJournal
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * Guidance for every MCP client, sent as the server's instructions: try a saved skill first, and
 * mark tasks so a successful one is archived. This is what makes the phone get better with use.
 */
const val SKILL_FIRST_INSTRUCTIONS =
    "Phone tasks on this device learn from use.\n" +
        "1. Before doing a task by hand, call skill_run with utterance = the user's request. status done or " +
        "handed_over: finished, tell the user. no_match: continue with step 2. failed: do step 2 with the same " +
        "request and go on from the current screen; the steps that ran are kept and the repaired skill replaces " +
        "the broken one.\n" +
        "2. Call task_begin with goal = the user's request, then do the task with the tools. Prefer click_node on " +
        "controls that show words, and open apps with open_app / shadow_open_app, so the steps can be replayed.\n" +
        "3. When the task succeeded, call task_end with success=true and params = the words from the request that " +
        "will change next time (e.g. {\"店名\": \"蜜雪冰城\"}). ARC then archives the steps as a skill, and the next " +
        "request like this runs without a model. Call task_end with success=false if it did not work.\n" +
        "Never pay or submit an order: hand over to the user at that point."

/** task_begin / task_end / task_status: task boundaries for the automatic archive. Action-only. */
class TaskTools
    @Inject
    constructor(
        private val journal: TaskJournal,
        private val coordinator: SkillCoordinator,
        private val actionExecutor: ActionExecutor,
    ) {
        fun begin(arguments: JsonObject?): CallToolResult {
            val goal = McpToolUtils.requireString(arguments, "goal").trim()
            if (goal.isEmpty()) throw McpToolException.InvalidParams("'goal' must not be blank")
            journal.begin(goal)
            return McpToolUtils.textResult("Task started: $goal. Do it with the tools, then call task_end.")
        }

        suspend fun end(arguments: JsonObject?): CallToolResult {
            val success = McpToolUtils.optionalBoolean(arguments, "success", true)
            val name = McpToolUtils.optionalString(arguments, "name", "").trim().ifEmpty { null }
            val params =
                (arguments?.get("params") as? JsonObject)?.mapValues { (k, v) ->
                    (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw McpToolException.InvalidParams("'params.$k' must be a string")
                } ?: emptyMap()
            val drop =
                (arguments?.get("drop") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    ?.toSet()
                    .orEmpty()
            val thenRead = McpToolUtils.optionalBoolean(arguments, "then_read", false)
            val keep = (arguments?.get("keep") as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0)
            val outcome = journal.end(success, name, params, ArchiveCut(drop, thenRead, keep))
            val archived = outcome.archived
            val text = if (archived != null) "archived: ${archived.name}\n${outcome.message}" else outcome.message
            return McpToolUtils.textResult(text)
        }

        fun status(): CallToolResult = McpToolUtils.textResult(journal.summary())

        /**
         * Takes the phone back to where the task stood after its first `step` recorded steps. A
         * few taps deeper is undone with the back key (`back` presses when the caller knows how
         * many screens were opened since, else one a tap); anything else, or `replay`, replays the
         * way from the app's launch. What was recorded after that point is dropped: it is no longer on
         * the way to where the phone is.
         */
        suspend fun goto(arguments: JsonObject?): CallToolResult {
            val step =
                (arguments?.get("step") as? JsonPrimitive)?.intOrNull
                    ?: throw McpToolException.InvalidParams("'step' must be a number")
            val way = journal.wayBack(step)
            if (way.route.isEmpty()) throw McpToolException.ActionFailed("Nothing is recorded up to step $step")
            val replay = McpToolUtils.optionalBoolean(arguments, "replay", false)
            val back =
                (arguments?.get("back") as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..MAX_BACK_PRESSES }
            val presses = if (replay) 0 else back ?: backPresses(way.since)
            val how = if (presses > 0) walkBack(presses) else replay(step, way.route)
            if (how.startsWith(DONE)) journal.rewind(step)
            return McpToolUtils.textResult(how)
        }

        private suspend fun walkBack(presses: Int): String {
            repeat(presses) {
                if (actionExecutor.pressBack().isFailure) return "status: failed\nthe back key did not work"
                delay(BACK_PAUSE_MS)
            }
            return "$DONE\n$WENT_BACK $presses screen(s)"
        }

        private suspend fun replay(
            step: Int,
            route: List<SkillStep>,
        ): String {
            val result = coordinator.runSteps("回到第 $step 步", route)
            val went = result.status == SkillRunStatus.DONE
            return if (went) "$DONE\nreplayed from the app's first screen" else "status: failed\n${result.message}"
        }

        /**
         * How many presses of the back key undo [since], or 0 when only a replay will do: each tap
         * is taken to have opened one screen, and anything that typed, launched or already went
         * back makes the count unsafe.
         */
        private fun backPresses(since: List<SkillStep>): Int {
            val simple = since.all { it is SkillStep.Tap || it is SkillStep.Scroll || it is SkillStep.Verify }
            val taps = since.count { it is SkillStep.Tap }
            return if (simple && taps in 1..MAX_BACK_PRESSES) taps else 0
        }

        companion object {
            const val TOOL_BEGIN = "task_begin"
            const val TOOL_END = "task_end"
            const val TOOL_STATUS = "task_status"
            const val TOOL_GOTO = "task_goto"

            /** First line of a jump that worked. */
            const val DONE = "status: done"

            /** In the answer when the jump was made with the back key, which is quick but not certain. */
            const val WENT_BACK = "went back"
            private const val MAX_BACK_PRESSES = 3
            private const val BACK_PAUSE_MS = 700L
        }
    }

fun registerTaskTools(
    server: Server,
    tools: TaskTools,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(TaskTools.TOOL_BEGIN)) {
        server.addTool(
            name = "$toolNamePrefix${TaskTools.TOOL_BEGIN}",
            description = "Starts a phone task (goal = the user's request). The steps you do next are recorded.",
            inputSchema = ToolSchema(properties = goalSchema(), required = listOf("goal")),
        ) { req -> tools.begin(req.arguments) }
    }
    if (perms.isToolEnabled(TaskTools.TOOL_END)) {
        server.addTool(
            name = "$toolNamePrefix${TaskTools.TOOL_END}",
            description =
                "Ends the task. success=true archives the recorded steps as a skill named after the goal (or " +
                    "'name'), with 'params' (placeholder → the word used this time) turned into {{placeholders}}; " +
                    "the next matching request replays it without a model. A skill of the same name is replaced.",
            inputSchema = ToolSchema(properties = endSchema()),
        ) { req -> tools.end(req.arguments) }
    }
    if (perms.isToolEnabled(TaskTools.TOOL_STATUS)) {
        server.addTool(
            name = "$toolNamePrefix${TaskTools.TOOL_STATUS}",
            description =
                "Shows the current task: goal, the recorded steps with their numbers, and anything that " +
                    "keeps it from being archived.",
            inputSchema = ToolSchema(properties = buildJsonObject { }),
        ) { tools.status() }
    }
}

/** task_goto, registered apart from the three task boundaries. */
fun registerTaskGoto(
    server: Server,
    tools: TaskTools,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (!perms.isToolEnabled(TaskTools.TOOL_GOTO)) return
    server.addTool(
        name = "$toolNamePrefix${TaskTools.TOOL_GOTO}",
        description =
            "Takes the phone back to where the current task stood after its first N recorded steps (see " +
                "task_status), by replaying them from the app's launch. Steps recorded after N are dropped.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        putJsonObject("step") {
                            put("type", "integer")
                            put("description", "How many recorded steps to be back at")
                        }
                        putJsonObject("replay") {
                            put("type", "boolean")
                            put("description", "Replay from the app's first screen even when the back key could do")
                        }
                        putJsonObject("back") {
                            put("type", "integer")
                            put("description", "Screens opened since that step, if known (1-3)")
                        }
                    },
                required = listOf("step"),
            ),
    ) { req -> tools.goto(req.arguments) }
}

/** Wraps every registered tool so its successful calls are written into the task journal. */
fun journalTools(
    server: Server,
    toolNamePrefix: String,
    recorder: ActionRecorder,
) {
    val wrapped =
        server.tools.values
            .filter { it.tool.name.removePrefix(toolNamePrefix) !in ActionRecorder.NOT_RECORDED }
            .map { registered ->
                val bare = registered.tool.name.removePrefix(toolNamePrefix)
                RegisteredTool(registered.tool) { req ->
                    recorder.around(bare, req.arguments) { registered.handler(req) }
                }
            }
    server.removeTools(wrapped.map { it.tool.name })
    server.addTools(wrapped)
}

private fun goalSchema() =
    buildJsonObject {
        putJsonObject("goal") {
            put("type", "string")
            put("description", "The user's request, in their words")
        }
    }

private fun endSchema() =
    buildJsonObject {
        putJsonObject("success") {
            put("type", "boolean")
            put("description", "Whether the task was done (default true)")
        }
        putJsonObject("name") {
            put("type", "string")
            put("description", "Skill name; default the goal. May contain {{placeholders}}")
        }
        putJsonObject("params") {
            put("type", "object")
            put("description", "Placeholder name → the word used this time, e.g. {\"店名\": \"蜜雪冰城\"}")
            putJsonObject("additionalProperties") { put("type", "string") }
        }
        putJsonObject("drop") {
            put("type", "array")
            put("description", "Numbers of recorded steps (see task_status) to leave out of the skill: detours")
            putJsonObject("items") { put("type", "integer") }
        }
        putJsonObject("keep") {
            put("type", "integer")
            put(
                "description",
                "Save only the first N recorded steps: the way to the screen, not what was then opened on it",
            )
        }
        putJsonObject("then_read") {
            put("type", "boolean")
            put(
                "description",
                "True when the request was answered by reading the last screen: a replay then asks for that reading",
            )
        }
    }
