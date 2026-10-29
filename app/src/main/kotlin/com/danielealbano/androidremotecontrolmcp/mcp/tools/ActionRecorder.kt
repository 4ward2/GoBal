package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.skill.HitPurpose
import com.danielealbano.androidremotecontrolmcp.services.skill.NodeSelector
import com.danielealbano.androidremotecontrolmcp.services.skill.SelectorFactory
import com.danielealbano.androidremotecontrolmcp.services.skill.SelectorResult
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillCoordinator
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillStep
import com.danielealbano.androidremotecontrolmcp.services.skill.TaskJournal
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** What a tool call will add to the task journal once it has succeeded. */
internal sealed interface Planned {
    data class Step(
        val step: SkillStep,
    ) : Planned

    /** Typing: the step is made after the call, from the text the field ended up with. */
    data class Typing(
        val target: NodeSelector?,
        val nodeId: String,
        val typed: String?,
    ) : Planned

    /** The app was started from its first screen: nothing recorded before this is on the way any more. */
    data class Restart(
        val step: SkillStep,
    ) : Planned

    /** tap_text: whether the words were a control's or read off the picture is known after the call. */
    data class TextTap(
        val text: String,
    ) : Planned

    /** A replay; [utterance] is the user's request when the skill was picked by it. */
    data class SkillRun(
        val utterance: String?,
    ) : Planned

    data class Blocked(
        val reason: String,
    ) : Planned

    data object Nothing : Planned
}

/**
 * Watches the model's tool calls and writes them into the [TaskJournal] as replayable steps, so a
 * task done once with a model can be archived and replayed without one. Controls are stored by
 * what they say (see [SelectorFactory]); anything a replay cannot repeat — a tap on an unlabelled
 * spot, a long press, a vault fill, typing into a password field or typing private data — keeps the
 * task from being archived instead of producing a skill that would do the wrong thing.
 */
@Singleton
class ActionRecorder
    @Inject
    constructor(
        private val windowReader: AccessibilityFreshWindowReader,
        private val nodeCache: AccessibilityNodeCache,
        private val journal: TaskJournal,
        private val coordinator: SkillCoordinator,
    ) {
        /** Seam for tests. */
        internal var readScreen: () -> MultiWindowResult = { windowReader.readVisible() }

        /** Seam for tests: the live text of a field and whether it is a password field. */
        internal var fieldState: (String) -> Pair<String?, Boolean>? = { nodeId ->
            nodeCache.get(nodeId)?.node?.let { node ->
                node.refresh()
                node.text?.toString() to node.isPassword
            }
        }

        suspend fun around(
            tool: String,
            arguments: JsonObject?,
            call: suspend () -> CallToolResult,
        ): CallToolResult {
            val planned =
                try {
                    plan(tool, arguments)
                } catch (e: IllegalStateException) {
                    Planned.Blocked("读不到屏幕：${e.message}")
                }
            val result = call()
            if (result.isError != true) apply(planned, result)
            return result
        }

        internal fun plan(
            tool: String,
            args: JsonObject?,
        ): Planned =
            when (tool) {
                "click_node", "tap_node" -> tapNode(args.stringArg("node_id"))
                "tap" -> tapAt(args.floatArg("x"), args.floatArg("y"))
                TextTapTools.TOOL_NAME -> args.stringArg("text")?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { Planned.TextTap(it) } ?: Planned.Nothing
                "type_append_text", "type_insert_text" -> typing(args.stringArg("node_id"), args.stringArg("text"))
                "type_replace_text" -> typing(args.stringArg("node_id"), null)
                "type_clear_text" -> typing(args.stringArg("node_id"), "")
                "press_home", "press_recents", "open_notifications", "open_quick_settings" -> leavesApp(tool)
                else -> ToolStepMapping.plan(tool, args)
            }

        private fun apply(
            planned: Planned,
            result: CallToolResult,
        ) {
            when (planned) {
                is Planned.Step -> {
                    journal.record(planned.step)
                }

                is Planned.Restart -> {
                    journal.record(planned.step, fromTheStart = true)
                }

                is Planned.Typing -> {
                    typed(planned)
                }

                is Planned.TextTap -> {
                    val fromPicture = ToolStepMapping.textOf(result).contains(TextTapTools.FROM_PICTURE)
                    journal.record(SkillStep.Tap(NodeSelector(text = planned.text), ocr = fromPicture))
                }

                // A replay that ran is part of the task (a broken skill's good steps, then the repair).
                is Planned.SkillRun -> {
                    val ran = !ToolStepMapping.textOf(result).contains(NO_MATCH)
                    if (ran) skillSteps(planned.utterance)
                }

                is Planned.Blocked -> {
                    journal.cannotReplay(planned.reason)
                }

                Planned.Nothing -> {
                    Unit
                }
            }
        }

        private fun tapNode(nodeId: String?): Planned =
            when (val found = nodeId?.let { SelectorFactory.forNode(readScreen().windows, it) }) {
                is SelectorResult.Found -> Planned.Step(SkillStep.Tap(found.selector))
                is SelectorResult.Unusable -> Planned.Blocked(found.reason)
                null -> Planned.Nothing
            }

        private fun tapAt(
            x: Float?,
            y: Float?,
        ): Planned {
            if (x == null || y == null) return Planned.Nothing
            return when (val found = SelectorFactory.atPoint(readScreen().windows, x, y)) {
                is SelectorResult.Found -> Planned.Step(SkillStep.Tap(found.selector))
                is SelectorResult.Unusable -> Planned.Blocked(found.reason)
            }
        }

        private fun typing(
            nodeId: String?,
            text: String?,
        ): Planned =
            when {
                nodeId == null -> {
                    Planned.Nothing
                }

                fieldState(nodeId)?.second == true -> {
                    Planned.Blocked("往密码框里输入")
                }

                else -> {
                    val found = SelectorFactory.forNode(readScreen().windows, nodeId, HitPurpose.INPUT)
                    // No id or words to find the field by: "the first input field" is what a replay can use.
                    val target = (found as? SelectorResult.Found)?.selector
                    Planned.Typing(target, nodeId, text)
                }
            }

        private fun skillSteps(utterance: String?) {
            // The request becomes the task's goal, so a task_begin with it continues this task.
            utterance?.let(journal::adopt)
            coordinator.lastResult.value?.let { journal.recordAll(it.executed) }
        }

        private fun typed(planned: Planned.Typing) {
            val text = fieldState(planned.nodeId)?.first ?: planned.typed
            when {
                text == null -> journal.cannotReplay("不知道输入框里最后是什么")
                McpToolUtils.contentRedactor.redact(text) != text -> journal.cannotReplay("输入了保险箱里的私人信息")
                else -> journal.record(SkillStep.Type(planned.target, text))
            }
        }

        /** Going home before anything else is setting up; in the middle of a task it cannot be replayed. */
        private fun leavesApp(tool: String): Planned =
            if (journal.isEmpty) {
                Planned.Nothing
            } else {
                Planned.Blocked("用了 $tool")
            }

        companion object {
            private const val NO_MATCH = "status: no_match"

            /** Tools whose effect a skill has no step for. */
            val UNREPLAYABLE =
                setOf(
                    "long_click_node",
                    "long_press",
                    "double_tap",
                    "pinch",
                    "custom_gesture",
                    "send_intent",
                    "press_key",
                    "vault_fill",
                    "system_autofill",
                )

            /** Tools that are not wrapped: they manage tasks and skills themselves. */
            val NOT_RECORDED =
                setOf(
                    "task_begin",
                    "task_end",
                    "task_status",
                    "task_goto",
                    "skill_save",
                    "skill_delete",
                    "skill_list",
                    "skill_get",
                )
        }
    }
