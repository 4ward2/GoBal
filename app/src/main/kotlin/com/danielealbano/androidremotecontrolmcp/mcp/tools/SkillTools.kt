package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.skill.Skill
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillCoordinator
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillParams
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRepository
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRunResult
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRunStatus
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillStep
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillText
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * Named, replayable flows ("skills"). A skill replays a flow the user recorded or an agent saved
 * without asking a model for each tap; it stops at checkpoints that fail and never taps a payment
 * or submit-order control. Output is device-derived (recorded labels, typed text): untrusted.
 */
class SkillTools
    @Inject
    constructor(
        private val repository: SkillRepository,
        private val coordinator: SkillCoordinator,
    ) {
        private val json = Json { ignoreUnknownKeys = true }
        private val compact = Json { encodeDefaults = false }

        suspend fun list(): CallToolResult {
            repository.load()
            val skills = repository.skills.value
            if (skills.isEmpty()) return McpToolUtils.untrustedTextResult("No skills saved yet.")
            val text =
                skills.joinToString("\n") { s ->
                    val params = s.params.joinToString(", ") { p -> "${p.name}=${p.defaultValue}" }
                    buildString {
                        append("- ${s.name} (${s.steps.size} steps, ${s.source.name.lowercase()}")
                        if (params.isNotEmpty()) append(", params: $params")
                        append(")")
                        if (s.description.isNotBlank()) append(": ${s.description}")
                    }
                }
            return McpToolUtils.untrustedTextResult("Saved skills:\n$text")
        }

        suspend fun get(arguments: JsonObject?): CallToolResult {
            val name = McpToolUtils.requireString(arguments, "name")
            repository.load()
            val skill = repository.find(name) ?: throw McpToolException.InvalidParams("No skill named '$name'")
            val steps = skill.steps.mapIndexed { i, s -> "${i + 1}. ${SkillText.describe(s)}" }.joinToString("\n")
            return McpToolUtils.untrustedTextResult("${compact.encodeToString(Skill.serializer(), skill)}\n\n$steps")
        }

        suspend fun run(arguments: JsonObject?): CallToolResult {
            val name = McpToolUtils.optionalString(arguments, "name", "").trim()
            val utterance = McpToolUtils.optionalString(arguments, "utterance", "").trim()
            val params = stringMap(arguments, "params")
            val result =
                when {
                    name.isNotEmpty() -> {
                        coordinator.run(name, params)
                    }

                    utterance.isNotEmpty() -> {
                        coordinator.runSpoken(utterance) ?: return McpToolUtils.textResult("status: no_match")
                    }

                    else -> {
                        throw McpToolException.InvalidParams("Give 'name' or 'utterance'")
                    }
                }
            return McpToolUtils.untrustedTextResult(format(result))
        }

        suspend fun save(arguments: JsonObject?): CallToolResult {
            val obj =
                arguments?.get("skill") as? JsonObject
                    ?: throw McpToolException.InvalidParams("Missing required object parameter: 'skill'")
            val replace = McpToolUtils.optionalBoolean(arguments, "replace", false)
            val skill = SkillParams.withCheckpoints(parse(obj).copy(runCount = 0, lastRunOk = null))
            val stored =
                repository.save(skill, replace).getOrElse { e ->
                    throw McpToolException.InvalidParams(e.message ?: "Skill not saved", e)
                }
            return McpToolUtils.textResult(
                "Saved skill '${stored.name}' with ${stored.steps.size} steps. Run it with skill_run.",
            )
        }

        suspend fun delete(arguments: JsonObject?): CallToolResult {
            val name = McpToolUtils.requireString(arguments, "name")
            return if (repository.delete(name)) {
                McpToolUtils.textResult("Deleted skill '$name'.")
            } else {
                throw McpToolException.InvalidParams("No skill named '$name'")
            }
        }

        /** A [SerializationException] is an [IllegalArgumentException]: either way the JSON is not a skill. */
        private fun parse(obj: JsonObject): Skill =
            try {
                json.decodeFromJsonElement(Skill.serializer(), withDefaultSource(obj))
            } catch (e: IllegalArgumentException) {
                throw McpToolException.InvalidParams("Invalid skill: ${e.message}", e)
            }

        /** Skills written through MCP come from an agent unless they say otherwise. */
        private fun withDefaultSource(obj: JsonObject): JsonObject =
            if (obj.containsKey("source")) obj else JsonObject(obj + ("source" to JsonPrimitive("agent")))

        private fun stringMap(
            arguments: JsonObject?,
            key: String,
        ): Map<String, String> {
            val element = arguments?.get(key) ?: return emptyMap()
            val obj =
                element as? JsonObject
                    ?: throw McpToolException.InvalidParams("'$key' must be an object of strings")
            return obj.mapValues { (k, v) ->
                (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw McpToolException.InvalidParams("'$key.$k' must be a string")
            }
        }

        companion object {
            private val STEPS_JSON = Json { encodeDefaults = false }
            private val STEP_LIST = ListSerializer(SkillStep.serializer())

            const val TOOL_LIST = "skill_list"
            const val TOOL_GET = "skill_get"
            const val TOOL_RUN = "skill_run"
            const val TOOL_SAVE = "skill_save"
            const val TOOL_DELETE = "skill_delete"

            /** In a run's result when the skill only navigated and the screen still has to be read. */
            const val THEN_READ_LINE = "then_read: true"

            /** Plain lines; `status:` comes first so scripts (agent_runner) can read it. */
            internal fun format(result: SkillRunResult): String =
                buildString {
                    appendLine("status: ${result.status.name.lowercase()}")
                    appendLine("skill: ${result.skillName}")
                    appendLine("steps: ${result.completedSteps}/${result.totalSteps}")
                    result.stoppedAt?.let { appendLine("stopped_at: $it") }
                    appendLine("message: ${result.message}")
                    if (result.thenRead) appendLine("$THEN_READ_LINE")
                    when (result.status) {
                        SkillRunStatus.DONE -> {}

                        SkillRunStatus.HANDED_OVER -> {
                            appendLine(
                                "next: the user takes over from here (payment, login or captcha); do not continue.",
                            )
                        }

                        SkillRunStatus.FAILED -> {
                            appendLine(
                                "next: nothing after the failed step ran. " +
                                    "Call get_screen_state and continue by hand if appropriate.",
                            )
                        }
                    }
                    if (result.log.isNotEmpty()) {
                        appendLine("log:")
                        result.log.forEach { appendLine("  $it") }
                    }
                    if (result.executed.isNotEmpty() && result.status != SkillRunStatus.DONE) {
                        // For clients that record their own tasks (agent_runner): the part to keep when repairing.
                        appendLine("executed_steps: ${STEPS_JSON.encodeToString(STEP_LIST, result.executed)}")
                    }
                    if (result.collected.isNotEmpty()) {
                        appendLine("collected: ${result.collected.size}")
                        result.savedTo?.let { appendLine("saved_to: $it") }
                        result.collected.forEach { appendLine("- ${it.line}") }
                    }
                }.trimEnd()
        }
    }

private const val SKILL_RUN_DESCRIPTION =
    "Replays a saved skill on the current target display (follows the shadow display). Prefer this " +
        "over manual steps when skill_list has a matching skill. Give 'name' + optional 'params', or " +
        "'utterance' (the user's sentence) to pick the skill and read its params by local rules. " +
        "Result starts with 'status: done | handed_over | failed | no_match'. handed_over means it " +
        "stopped for the user (payment etc.): do not continue."

private const val SKILL_SAVE_DESCRIPTION =
    "Saves a named skill so the flow can later be replayed with skill_run instead of step-by-step tool calls. " +
        "'skill' = {\"name\": \"点{{店名}}外卖\", \"description\": \"...\", \"params\": [{\"name\": \"店名\", " +
        "\"default\": \"蜜雪冰城\"}], \"steps\": [...]}. Step objects (field \"type\"): " +
        "{\"type\":\"launch\",\"package\":\"com.sankuai.meituan\",\"uri\":null}; " +
        "{\"type\":\"tap\",\"target\":{\"text\":\"搜索\",\"match\":\"exact|contains\",\"resource_id\":null," +
        "\"class_name\":null,\"package\":null},\"scroll\":true,\"ocr\":false} (ocr:true = the control is " +
        "not in the accessibility tree; its text is found by OCR on a screenshot and tapped); " +
        "{\"type\":\"collect\",\"max_items\":200,\"max_scrolls\":60,\"pause_ms\":1500} (scroll the main list " +
        "to the end, returning every entry's texts; stops at 没有更多/到底了, when nothing new appears, or at a limit); " +
        "{\"type\":\"type\",\"target\":null|{...},\"text\":\"{{店名}}\"}; " +
        "{\"type\":\"verify\",\"text\":\"{{店名}}\",\"ocr\":true,\"timeout_ms\":6000}; " +
        "{\"type\":\"wait\",\"target\":{...},\"timeout_ms\":8000}; {\"type\":\"scroll\",\"direction\":\"down|up\"}; " +
        "{\"type\":\"back\"}; {\"type\":\"hand_over\",\"reason\":\"...\"}. " +
        "Address controls by visible text / description / resource id, never coordinates. Use {{param}} for " +
        "values that change between runs. A verify step is added automatically after taps on {{param}} text. " +
        "Replays stop before any payment / submit-order button."

fun registerSkillTools(
    server: Server,
    tools: SkillTools,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    registerSkillReadTools(server, tools, toolNamePrefix, perms)
    if (perms.isToolEnabled(SkillTools.TOOL_RUN)) {
        server.addTool(
            name = "$toolNamePrefix${SkillTools.TOOL_RUN}",
            description = SKILL_RUN_DESCRIPTION,
            inputSchema = ToolSchema(properties = skillRunProperties()),
        ) { req -> tools.run(req.arguments) }
    }
    if (perms.isToolEnabled(SkillTools.TOOL_SAVE)) {
        server.addTool(
            name = "$toolNamePrefix${SkillTools.TOOL_SAVE}",
            description = SKILL_SAVE_DESCRIPTION,
            inputSchema = ToolSchema(properties = skillSaveProperties(), required = listOf("skill")),
        ) { req -> tools.save(req.arguments) }
    }
    if (perms.isToolEnabled(SkillTools.TOOL_DELETE)) {
        server.addTool(
            name = "$toolNamePrefix${SkillTools.TOOL_DELETE}",
            description = "Deletes a saved skill.",
            inputSchema = ToolSchema(properties = skillNameProperty(), required = listOf("name")),
        ) { req -> tools.delete(req.arguments) }
    }
}

/** skill_list and skill_get: reading what is saved. */
private fun registerSkillReadTools(
    server: Server,
    tools: SkillTools,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(SkillTools.TOOL_LIST)) {
        server.addTool(
            name = "$toolNamePrefix${SkillTools.TOOL_LIST}",
            description = "Lists saved skills (named, replayable UI flows) with their parameters and defaults.",
            inputSchema = ToolSchema(properties = buildJsonObject { }),
        ) { tools.list() }
    }
    if (perms.isToolEnabled(SkillTools.TOOL_GET)) {
        server.addTool(
            name = "$toolNamePrefix${SkillTools.TOOL_GET}",
            description = "Shows one skill's steps as JSON plus a readable list.",
            inputSchema = ToolSchema(properties = skillNameProperty(), required = listOf("name")),
        ) { req -> tools.get(req.arguments) }
    }
}

private fun skillNameProperty(): JsonObject =
    buildJsonObject {
        putJsonObject("name") {
            put("type", "string")
            put("description", "Skill name as listed by skill_list")
        }
    }

private fun skillRunProperties(): JsonObject =
    buildJsonObject {
        putJsonObject("name") {
            put("type", "string")
            put("description", "Skill name")
        }
        putJsonObject("params") {
            put("type", "object")
            put("description", "Parameter values, e.g. {\"店名\": \"瑞幸\"}; defaults fill the rest")
            putJsonObject("additionalProperties") { put("type", "string") }
        }
        putJsonObject("utterance") {
            put("type", "string")
            put("description", "The user's sentence, matched against skill names locally")
        }
    }

private fun skillSaveProperties(): JsonObject =
    buildJsonObject {
        putJsonObject("skill") {
            put("type", "object")
            put("description", "The skill definition (see tool description)")
        }
        putJsonObject("replace") {
            put("type", "boolean")
            put("description", "Overwrite a skill with the same name (default false)")
        }
    }
