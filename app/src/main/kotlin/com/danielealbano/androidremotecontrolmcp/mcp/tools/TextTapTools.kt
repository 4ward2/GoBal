package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import com.danielealbano.androidremotecontrolmcp.services.skill.HitPurpose
import com.danielealbano.androidremotecontrolmcp.services.skill.NodeSelector
import com.danielealbano.androidremotecontrolmcp.services.skill.OcrHit
import com.danielealbano.androidremotecontrolmcp.services.skill.PaymentGuard
import com.danielealbano.androidremotecontrolmcp.services.skill.ScreenTextReader
import com.danielealbano.androidremotecontrolmcp.services.skill.SelectorMatcher
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/** Where some words are on the screen, and whether they had to be read off a picture of it. */
internal data class TextSpot(
    val x: Float,
    val y: Float,
    /** Everything written at that spot (a button's whole label), which is what a guard must judge. */
    val read: String,
    val fromPicture: Boolean,
)

/**
 * tap_text: taps words wherever they are drawn. A control that says exactly those words is tapped
 * at its centre; words that are only part of a control (a web page or a tab bar that reaches the
 * accessibility tree as one node) or belong to no control at all are found by reading a
 * screenshot. Either way the tap is made by what the screen says, so it can be replayed as a
 * skill step. Action-only: the answer carries nothing read from the screen.
 */
class TextTapTools
    @Inject
    constructor(
        private val windowReader: AccessibilityFreshWindowReader,
        private val actionExecutor: ActionExecutor,
        private val screenText: ScreenTextReader,
    ) {
        /** Seams for tests. */
        internal var readWindows: () -> List<WindowData> = { windowReader.readVisible().windows }
        internal var locate: suspend (String) -> OcrHit? = { screenText.locate(it) }
        internal var tapAt: suspend (Float, Float) -> Result<Unit> = { x, y -> actionExecutor.tap(x, y) }

        suspend fun tap(arguments: JsonObject?): CallToolResult {
            val text = wanted(arguments)
            val spot =
                inControls(readWindows(), text)
                    ?: locate(text)?.let { TextSpot(it.x, it.y, it.read, fromPicture = true) }
                    ?: throw McpToolException.ActionFailed(
                        "\"$text\" is not on the screen: no control says it and it is not in a screenshot either. " +
                            "Use the exact words shown, or scroll to them first.",
                    )
            refusePayment(spot.read)
            tapAt(spot.x, spot.y).getOrElse { throw McpToolException.ActionFailed("The tap on \"$text\" failed") }
            return McpToolUtils.textResult("Tapped \"$text\", ${if (spot.fromPicture) FROM_PICTURE else FROM_CONTROL}.")
        }

        /** The words to tap; blank ones and words that pay are refused before anything is looked for. */
        private fun wanted(arguments: JsonObject?): String {
            val text = McpToolUtils.requireString(arguments, "text").trim()
            if (text.isEmpty()) throw McpToolException.InvalidParams("'text' must not be blank")
            refusePayment(text)
            return text
        }

        /** Paying is for the user: neither the words asked for nor the label they sit in may be a pay button. */
        private fun refusePayment(words: String) {
            if (PaymentGuard.blocks(words)) {
                throw McpToolException.ActionFailed("\"${words.take(LABEL_CHARS)}\" pays or orders: the user does that")
            }
        }

        companion object {
            const val TOOL_NAME = "tap_text"
            const val FROM_PICTURE = "found by reading the screen picture"
            const val FROM_CONTROL = "a control with these words"
            private const val LABEL_CHARS = 20

            /** The control that says exactly [text], if there is one. */
            internal fun inControls(
                windows: List<WindowData>,
                text: String,
            ): TextSpot? {
                val hit = SelectorMatcher.find(windows, NodeSelector(text = text), HitPurpose.TAP) ?: return null
                val box = hit.node.bounds
                val x = (box.left + box.right) / 2f
                val y = (box.top + box.bottom) / 2f
                return TextSpot(x, y, hit.label, fromPicture = false)
            }
        }
    }

fun registerTextTapTools(
    server: Server,
    tools: TextTapTools,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (!perms.isToolEnabled(TextTapTools.TOOL_NAME)) return
    server.addTool(
        name = "$toolNamePrefix${TextTapTools.TOOL_NAME}",
        description =
            "Taps the given words wherever they are on the screen. Use it when the words are only part of a " +
                "long node (a web page, a tab bar or a list merged into one node) or are in no node at all. " +
                "Give the exact words as shown, as few as identify the spot. Never taps a pay or order button.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        putJsonObject("text") {
                            put("type", "string")
                            put("description", "The exact words to tap, as shown on the screen")
                        }
                    },
                required = listOf("text"),
            ),
    ) { req -> tools.tap(req.arguments) }
}
