package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.view.autofill.AutofillManager
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * Hands an input field to the phone's own password vault, i.e. the system autofill service
 * the user enabled (ColorOS 密码保险箱, MIUI 密码管理, Google Password Manager, …).
 *
 * The agent only picks the field; focusing an autofillable field makes the Android autofill
 * framework ask that vault for suggestions. Picking the entry, the fingerprint check and the
 * filling itself all happen inside the vault, so no value ever passes through this app.
 */
class SystemAutofillTools
    @Inject
    constructor(
        private val windowReader: AccessibilityFreshWindowReader,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        /** Seam for tests: the enabled autofill service, or null when none is enabled. */
        internal var activeVault: () -> String? = {
            accessibilityServiceProvider
                .getContext()
                ?.getSystemService(AutofillManager::class.java)
                ?.autofillServiceComponentName
                ?.flattenToShortString()
        }

        internal var readWindows: FreshWindowReader = FreshWindowReader { windowReader.read() }

        suspend fun requestFill(arguments: JsonObject?): CallToolResult {
            val nodeId = McpToolUtils.requireString(arguments, "node_id")
            val vault = activeVault()
            val windows = if (vault == null) emptyList() else readWindows.read().windows
            val field = windows.firstNotNullOfOrNull { findNode(it.tree, nodeId) }
            val problem =
                when {
                    vault == null -> {
                        McpToolException.ActionFailed(
                            "No system password vault is enabled on this phone. The user can turn one on " +
                                "in Settings → Passwords / Autofill service.",
                        )
                    }

                    field == null -> {
                        McpToolException.NodeNotFound("Node '$nodeId' not found; call get_screen_state again")
                    }

                    !field.editable -> {
                        McpToolException.InvalidParams("Node '$nodeId' is not an input field")
                    }

                    else -> {
                        null
                    }
                }
            if (problem != null) throw problem
            // A real tap, not an accessibility click: web inputs (WebView / Chromium) only take input
            // focus, and so only start an autofill request, from a touch.
            val b = checkNotNull(field).bounds
            val tapped = actionExecutor.tap((b.left + b.right) / 2f, (b.top + b.bottom) / 2f)
            if (tapped.isFailure) {
                throw McpToolException.ActionFailed("Could not focus node '$nodeId'")
            }
            return McpToolUtils.textResult(
                "Tapped node $nodeId and handed it to the system vault ($vault). The vault shows its " +
                    "suggestions to the user; the user picks the entry and confirms with fingerprint or " +
                    "screen lock, and the vault fills the field. Nothing was typed by the assistant. " +
                    "Call get_screen_state afterwards to see whether the field was filled.",
            )
        }

        companion object {
            const val TOOL_NAME = "system_autofill"
        }
    }

private fun findNode(
    node: AccessibilityNodeData,
    nodeId: String,
): AccessibilityNodeData? = if (node.id == nodeId) node else node.children.firstNotNullOfOrNull { findNode(it, nodeId) }

fun registerSystemAutofillTools(
    server: Server,
    tools: SystemAutofillTools,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (!perms.isToolEnabled(SystemAutofillTools.TOOL_NAME)) return
    server.addTool(
        name = "$toolNamePrefix${SystemAutofillTools.TOOL_NAME}",
        description =
            "Hands an input field (account, password, …) to the phone's own password vault. You pick the " +
                "field; the vault shows its suggestions, the user chooses and confirms with fingerprint, " +
                "and the vault fills it. Values are never visible to you. Fails if no system vault is enabled.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        putJsonObject("node_id") {
                            put("type", "string")
                            put("description", "Input field's node_id from get_screen_state")
                        }
                    },
                required = listOf("node_id"),
            ),
    ) { req -> tools.requestFill(req.arguments) }
}
