package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TargetDisplay
import com.danielealbano.androidremotecontrolmcp.services.shadow.ShadowDisplayClient
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * Shadow display ("影子屏") tools: run an app on a background virtual display so the agent can
 * work in it while the user keeps using the physical screen.
 *
 * Creating the display and launching apps onto it is done by the external shadow display
 * daemon (see [ShadowDisplayClient]); reading and acting on it reuses every existing tool via
 * [TargetDisplay]. A tall shadow display ([ShadowDisplayTools.TOOL_RESIZE]) lays out long lists
 * in one pass, so a single `get_screen_state` returns every item the app has already loaded.
 */
class ShadowDisplayTools
    @Inject
    constructor(
        private val client: ShadowDisplayClient,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        suspend fun openApp(arguments: JsonObject?): CallToolResult {
            val packageId = McpToolUtils.requireString(arguments, "package_id").trim()
            val width = McpToolUtils.optionalInt(arguments, "width", DEFAULT_WIDTH)
            val height = McpToolUtils.optionalInt(arguments, "height", DEFAULT_HEIGHT)
            val dpi = McpToolUtils.optionalInt(arguments, "dpi", DEFAULT_DPI)
            val uri = McpToolUtils.optionalString(arguments, "uri", "").trim()
            validateSize(width, height, dpi)
            validateOpen(packageId, uri, accessibilityServiceProvider.getContext()?.packageName)

            val displayId = existingDisplayId() ?: createDisplay(width, height, dpi)
            val params =
                buildMap {
                    put("display_id", JsonPrimitive(displayId))
                    put("package", JsonPrimitive(packageId))
                    if (uri.isNotEmpty()) put("uri", JsonPrimitive(uri))
                }
            call("open", params)
            TargetDisplay.set(displayId)
            Log.i(TAG, "shadow_open_app: $packageId on display $displayId")
            val what = if (uri.isEmpty()) packageId else "$uri in $packageId"
            return McpToolUtils.textResult(
                "Opened $what on shadow display $displayId. " +
                    "All screen/gesture/node tools now act on this display; the user's physical " +
                    "screen is untouched. Call shadow_hand_over to give the app to the user, or " +
                    "shadow_close when done.",
            )
        }

        suspend fun status(): CallToolResult {
            val reply = call("status", emptyMap())
            val displays = reply["displays"] as? JsonArray ?: JsonArray(emptyList())
            val result =
                buildJsonObject {
                    put("target_display_id", TargetDisplay.id)
                    put("displays", displays)
                }
            return McpToolUtils.untrustedTextResult(result.toString())
        }

        suspend fun resize(arguments: JsonObject?): CallToolResult {
            val displayId = requireShadowTarget()
            val height = McpToolUtils.requireInt(arguments, "height")
            val width = McpToolUtils.optionalInt(arguments, "width", DEFAULT_WIDTH)
            val dpi = McpToolUtils.optionalInt(arguments, "dpi", DEFAULT_DPI)
            validateSize(width, height, dpi)
            call(
                "resize",
                mapOf(
                    "display_id" to JsonPrimitive(displayId),
                    "width" to JsonPrimitive(width),
                    "height" to JsonPrimitive(height),
                    "dpi" to JsonPrimitive(dpi),
                ),
            )
            return McpToolUtils.textResult(
                "Shadow display $displayId resized to ${width}x$height/$dpi. Wait for the app to " +
                    "re-layout (wait_for_idle), then get_screen_state returns everything that fits.",
            )
        }

        suspend fun handOver(arguments: JsonObject?): CallToolResult {
            val packageId = McpToolUtils.requireString(arguments, "package_id").trim()
            if (!PACKAGE_REGEX.matches(packageId)) {
                throw McpToolException.InvalidParams("Invalid package_id: '$packageId'")
            }
            call("to_main", mapOf("package" to JsonPrimitive(packageId)))
            TargetDisplay.reset()
            return McpToolUtils.textResult(
                "$packageId moved to the physical screen for the user; target display reset to 0.",
            )
        }

        suspend fun close(): CallToolResult {
            val displayId = existingDisplayId()
            if (displayId == null) {
                TargetDisplay.reset()
                return McpToolUtils.textResult("No shadow display is open; target display is 0.")
            }
            call("destroy", mapOf("display_id" to JsonPrimitive(displayId)))
            TargetDisplay.reset()
            return McpToolUtils.textResult(
                "Shadow display $displayId closed (apps on it were finished); target display reset to 0.",
            )
        }

        suspend fun setTarget(arguments: JsonObject?): CallToolResult {
            val displayId = McpToolUtils.requireInt(arguments, "display_id")
            if (displayId < 0) throw McpToolException.InvalidParams("display_id must be >= 0")
            if (displayId != 0 && displayId !in shadowDisplayIds()) {
                throw McpToolException.InvalidParams(
                    "Display $displayId is not an open shadow display (see shadow_status)",
                )
            }
            TargetDisplay.set(displayId)
            return McpToolUtils.textResult(
                if (displayId == 0) "Target display: physical screen (0)." else "Target display: shadow $displayId.",
            )
        }

        private suspend fun existingDisplayId(): Int? = shadowDisplayIds().lastOrNull()

        private suspend fun shadowDisplayIds(): List<Int> {
            val displays = call("status", emptyMap())["displays"] as? JsonArray ?: return emptyList()
            return displays.mapNotNull { (it.jsonObject["display_id"] as? JsonPrimitive)?.intOrNull }
        }

        private suspend fun createDisplay(
            width: Int,
            height: Int,
            dpi: Int,
        ): Int {
            val reply =
                call(
                    "create",
                    mapOf(
                        "width" to JsonPrimitive(width),
                        "height" to JsonPrimitive(height),
                        "dpi" to JsonPrimitive(dpi),
                    ),
                )
            val display = reply["display"] as? JsonObject
            return (display?.get("display_id") as? JsonPrimitive)?.intOrNull
                ?: throw McpToolException.ActionFailed("Shadow display daemon returned no display id")
        }

        private suspend fun call(
            cmd: String,
            params: Map<String, JsonElement>,
        ): JsonObject =
            client.request(cmd, params).getOrElse { e ->
                throw McpToolException.ActionFailed("Shadow display: ${e.message}")
            }

        companion object {
            const val TOOL_OPEN_APP = "shadow_open_app"
            const val TOOL_STATUS = "shadow_status"
            const val TOOL_RESIZE = "shadow_resize"
            const val TOOL_HAND_OVER = "shadow_hand_over"
            const val TOOL_CLOSE = "shadow_close"
            const val TOOL_SET_TARGET = "set_target_display"

            const val DEFAULT_WIDTH = 1080
            const val DEFAULT_HEIGHT = 2340
            const val DEFAULT_DPI = 420
            const val MIN_SIDE = 200
            const val MAX_SIDE = 16384
            const val MAX_PIXELS = 1440L * 12000L
            const val MIN_DPI = 80
            const val MAX_DPI = 800

            internal val PACKAGE_REGEX = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")

            // scheme:rest without whitespace or quotes; the daemon passes it as one argv element.
            internal val URI_REGEX = Regex("[A-Za-z][A-Za-z0-9+.-]{0,31}:[^\\s'\"]{1,2000}")
            private const val TAG = "MCP:ShadowDisplayTools"
        }
    }

private fun requireShadowTarget(): Int {
    if (TargetDisplay.isDefault) {
        throw McpToolException.ActionFailed(
            "The target is the physical screen; open an app with shadow_open_app first",
        )
    }
    return TargetDisplay.id
}

private fun validateOpen(
    packageId: String,
    uri: String,
    selfPackage: String?,
) {
    val error =
        when {
            !ShadowDisplayTools.PACKAGE_REGEX.matches(packageId) -> {
                "Invalid package_id: '$packageId'"
            }

            // Its screens (vault settings, approvals) must stay in front of the user, never hidden.
            packageId == selfPackage -> {
                "The assistant itself cannot be opened on a shadow display"
            }

            uri.isNotEmpty() && !ShadowDisplayTools.URI_REGEX.matches(uri) -> {
                "Invalid uri"
            }

            else -> {
                null
            }
        }
    if (error != null) throw McpToolException.InvalidParams(error)
}

private fun validateSize(
    width: Int,
    height: Int,
    dpi: Int,
) {
    val sides = ShadowDisplayTools.MIN_SIDE..ShadowDisplayTools.MAX_SIDE
    val dpis = ShadowDisplayTools.MIN_DPI..ShadowDisplayTools.MAX_DPI
    val error =
        when {
            width !in sides || height !in sides -> {
                "width/height must be $sides"
            }

            width.toLong() * height > ShadowDisplayTools.MAX_PIXELS -> {
                "display too large: at most ${ShadowDisplayTools.MAX_PIXELS} pixels"
            }

            dpi !in dpis -> {
                "dpi must be $dpis"
            }

            else -> {
                null
            }
        }
    if (error != null) throw McpToolException.InvalidParams(error)
}

private val EMPTY_SCHEMA = ToolSchema(properties = buildJsonObject {}, required = emptyList())

private fun packageSchema(
    description: String,
    withSize: Boolean,
) = ToolSchema(
    properties =
        buildJsonObject {
            putJsonObject("package_id") {
                put("type", "string")
                put("description", description)
            }
            if (withSize) {
                putJsonObject("uri") {
                    put("type", "string")
                    put("description", "Optional link or deep link to open in the app (https://…, imeituan://…)")
                }
                sizeProperties(this, "Display height in px (default ${ShadowDisplayTools.DEFAULT_HEIGHT})")
            }
        },
    required = listOf("package_id"),
)

private fun sizeProperties(
    builder: JsonObjectBuilder,
    heightDescription: String,
) {
    builder.putJsonObject("width") {
        put("type", "integer")
        put("description", "Display width in px (default ${ShadowDisplayTools.DEFAULT_WIDTH})")
    }
    builder.putJsonObject("height") {
        put("type", "integer")
        put("description", heightDescription)
    }
    builder.putJsonObject("dpi") {
        put("type", "integer")
        put("description", "Display density (default ${ShadowDisplayTools.DEFAULT_DPI})")
    }
}

fun registerShadowDisplayTools(
    server: Server,
    tools: ShadowDisplayTools,
    prefix: String,
    perms: ToolPermissionsConfig,
) {
    registerShadowLaunchTools(server, tools, prefix, perms)
    registerShadowControlTools(server, tools, prefix, perms)
}

private fun registerShadowLaunchTools(
    server: Server,
    tools: ShadowDisplayTools,
    prefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(ShadowDisplayTools.TOOL_OPEN_APP)) {
        server.addTool(
            name = "$prefix${ShadowDisplayTools.TOOL_OPEN_APP}",
            description =
                "Opens an app on a background shadow display (a virtual screen) and makes it " +
                    "the target of all screen/gesture/node tools. The user's physical screen is " +
                    "not touched, and the shadow display keeps running while the phone screen " +
                    "is off. Requires the PhoneMCP shadow display daemon on the device. " +
                    "Pages marked secure (payments, some logins) render black in screenshots " +
                    "but still appear in get_screen_state.",
            inputSchema = packageSchema("Package name, e.g. com.taobao.taobao", withSize = true),
        ) { req -> tools.openApp(req.arguments) }
    }
    if (perms.isToolEnabled(ShadowDisplayTools.TOOL_STATUS)) {
        server.addTool(
            name = "$prefix${ShadowDisplayTools.TOOL_STATUS}",
            description = "Lists open shadow displays (id, size, top activity) and the current target display.",
            inputSchema = EMPTY_SCHEMA,
        ) { _ -> tools.status() }
    }
    if (perms.isToolEnabled(ShadowDisplayTools.TOOL_RESIZE)) {
        server.addTool(
            name = "$prefix${ShadowDisplayTools.TOOL_RESIZE}",
            description =
                "Resizes the target shadow display. A tall display (e.g. height 9000) makes " +
                    "list screens lay out every item the app has already loaded, so one " +
                    "get_screen_state returns them all instead of scrolling screen by screen. " +
                    "It cannot reveal items the app has not fetched yet: scroll to the end to " +
                    "trigger the next page first.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject { sizeProperties(this, "New height in px (required)") },
                    required = listOf("height"),
                ),
        ) { req -> tools.resize(req.arguments) }
    }
}

private fun registerShadowControlTools(
    server: Server,
    tools: ShadowDisplayTools,
    prefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(ShadowDisplayTools.TOOL_HAND_OVER)) {
        server.addTool(
            name = "$prefix${ShadowDisplayTools.TOOL_HAND_OVER}",
            description =
                "Moves an app from the shadow display to the physical screen so the user can " +
                    "take over (login, captcha, payment confirmation), and resets the target " +
                    "display to the physical screen.",
            inputSchema = packageSchema("Package to bring to the physical screen", withSize = false),
        ) { req -> tools.handOver(req.arguments) }
    }
    if (perms.isToolEnabled(ShadowDisplayTools.TOOL_CLOSE)) {
        server.addTool(
            name = "$prefix${ShadowDisplayTools.TOOL_CLOSE}",
            description =
                "Closes the shadow display (apps on it are finished) and resets the target " +
                    "display to the physical screen.",
            inputSchema = EMPTY_SCHEMA,
        ) { _ -> tools.close() }
    }
    if (perms.isToolEnabled(ShadowDisplayTools.TOOL_SET_TARGET)) {
        server.addTool(
            name = "$prefix${ShadowDisplayTools.TOOL_SET_TARGET}",
            description =
                "Sets which display the screen/gesture/node tools act on: 0 = physical screen, " +
                    "otherwise an open shadow display id.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("display_id") {
                                put("type", "integer")
                                put("description", "0 for the physical screen, or a shadow display id")
                            }
                        },
                    required = listOf("display_id"),
                ),
        ) { req -> tools.setTarget(req.arguments) }
    }
}
