package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.services.skill.SkillScrollDirection
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillStep
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlin.math.abs

/**
 * The part of [ActionRecorder]'s mapping from tool calls to skill steps that needs neither the
 * screen nor the journal: scrolls, swipes, launches, the back key and tools a skill cannot repeat.
 */
internal object ToolStepMapping {
    fun plan(
        tool: String,
        args: JsonObject?,
    ): Planned =
        when (tool) {
            "scroll" -> scroll(args.stringArg("direction"))
            "swipe" -> swipe(args)
            "press_back" -> Planned.Step(SkillStep.Back)
            "open_app" -> open(args)
            "shadow_open_app" -> launch(args.stringArg("package_id"), args.stringArg("uri"))
            "open_uri" -> launch(args.stringArg("package_name"), args.stringArg("uri"))
            "skill_run" -> Planned.SkillRun(args.stringArg("utterance"))
            in ActionRecorder.UNREPLAYABLE -> Planned.Blocked("用了 $tool")
            else -> Planned.Nothing
        }

    /** open_app; with `fresh` the app starts over, and so does the recorded way through it. */
    private fun open(args: JsonObject?): Planned {
        val launched = launch(args.stringArg("package_id"), null)
        val fresh = (args?.get("fresh") as? JsonPrimitive)?.booleanOrNull == true
        return if (fresh && launched is Planned.Step) Planned.Restart(launched.step) else launched
    }

    fun textOf(result: CallToolResult): String {
        val texts = result.content.filterIsInstance<TextContent>()
        return texts.joinToString(" ") { it.text }
    }

    private fun scroll(direction: String?): Planned =
        when (direction?.lowercase()) {
            "down" -> Planned.Step(SkillStep.Scroll(SkillScrollDirection.DOWN))
            "up" -> Planned.Step(SkillStep.Scroll(SkillScrollDirection.UP))
            else -> Planned.Blocked("左右滑动")
        }

    private fun swipe(args: JsonObject?): Planned {
        val dx = (args.floatArg("x2") ?: 0f) - (args.floatArg("x1") ?: 0f)
        val dy = (args.floatArg("y1") ?: 0f) - (args.floatArg("y2") ?: 0f)
        return when {
            dy != 0f && abs(dy) >= abs(dx) -> scroll(if (dy > 0) "down" else "up")
            else -> Planned.Blocked("左右滑动")
        }
    }

    private fun launch(
        packageName: String?,
        uri: String?,
    ): Planned =
        if (packageName.isNullOrBlank()) {
            Planned.Blocked("打开链接时没有指明应用")
        } else {
            Planned.Step(SkillStep.Launch(packageName.trim(), uri?.takeIf { it.isNotBlank() }))
        }
}

internal fun JsonObject?.stringArg(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject?.floatArg(key: String): Float? = (this?.get(key) as? JsonPrimitive)?.floatOrNull
