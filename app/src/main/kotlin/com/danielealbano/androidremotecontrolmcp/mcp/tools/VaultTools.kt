package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import com.danielealbano.androidremotecontrolmcp.services.vault.FillDecision
import com.danielealbano.androidremotecontrolmcp.services.vault.FillTarget
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultFillRecord
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultPolicy
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultRepository
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultSlot
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import javax.inject.Inject

/** A fresh accessibility read of the target display that also refreshes the node cache. */
fun interface FreshWindowReader {
    fun read(): MultiWindowResult
}

class AccessibilityFreshWindowReader
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
    ) : FreshWindowReader {
        override fun read(): MultiWindowResult = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)

        /** Only what is on screen: subtrees the user cannot see are not read (much faster on long lists). */
        fun readVisible(): MultiWindowResult {
            val visibleOnly = true
            return getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache, visibleOnly)
        }
    }

/**
 * Personal vault tools. The model sees slot ids and labels only and chooses *where* a value
 * goes; this code decides *whether* it may go there ([VaultPolicy]), asks the user when
 * needed, and writes the value itself. No response ever contains a value.
 */
class VaultTools
    @Inject
    constructor(
        private val repository: VaultRepository,
        private val approvals: VaultApprovalCoordinator,
        private val windowReader: AccessibilityFreshWindowReader,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
        private val actionExecutor: ActionExecutor,
    ) {
        /** Seam for tests: replaces [windowReader]. */
        internal var readWindows: FreshWindowReader = FreshWindowReader { windowReader.read() }

        suspend fun listSlots(): CallToolResult {
            repository.load()
            val json =
                buildJsonObject {
                    put(
                        "note",
                        "Values are never shown. Use vault_fill with a slot id and an input field's " +
                            "node_id; the phone writes the value and may ask the user first.",
                    )
                    putJsonArray("slots") { repository.slots.value.forEach { add(slotJson(it)) } }
                }
            return McpToolUtils.textResult(json.toString())
        }

        suspend fun fill(arguments: JsonObject?): CallToolResult {
            val nodeId = McpToolUtils.requireString(arguments, "node_id")
            val slotId = McpToolUtils.requireString(arguments, "slot")
            repository.load()
            val slot =
                repository.slots.value.firstOrNull { it.id == slotId }
                    ?: throw McpToolException.InvalidParams("Unknown vault slot '$slotId' (see vault_list_slots)")
            val (windows, target) = locate(nodeId)
            val pkg = target.packageName.orEmpty()
            val asked = authorize(slot, target)
            val current =
                if (asked) {
                    // The approval card covered the app for up to a minute: wait for the field to
                    // come back, then check again that it is still the same kind of field.
                    awaitReturn(nodeId, pkg)
                    recheck(slot, nodeId, target)
                } else {
                    windows
                }
            write(slot, pkg, nodeId, current)
            Log.i(TAG, "vault_fill slot=${slot.id} pkg=$pkg")
            return McpToolUtils.textResult(
                "Filled '${slot.label}' (slot ${slot.id}) into node $nodeId in $pkg. " +
                    "The value appears as ‹vault:${slot.id}› in later screen reads.",
            )
        }

        /** Reads the live node: its app, and whether it is an enabled, editable, password field. */
        private fun locate(nodeId: String): Pair<List<WindowData>, FillTarget> {
            val windows = readWindows.read().windows
            val window =
                windows.firstOrNull { containsNode(it.tree, nodeId) }
                    ?: throw McpToolException.NodeNotFound("Node '$nodeId' not found; call get_screen_state again")
            val real = nodeCache.get(nodeId)?.node
            real?.refresh()
            val target =
                FillTarget(
                    packageName = window.packageName,
                    editable = real?.isEditable ?: false,
                    enabled = real?.isEnabled ?: false,
                    password = real?.isPassword ?: false,
                )
            return windows to target
        }

        /** Returns true when the user was asked (and approved) on the phone. */
        private suspend fun authorize(
            slot: VaultSlot,
            target: FillTarget,
        ): Boolean {
            val pkg = target.packageName.orEmpty()
            val context = accessibilityServiceProvider.getContext()
            // One browser hosts every website, so an "always allow" there would cover all sites.
            val browser = isBrowser(context, pkg)
            val decided =
                VaultPolicy.decide(
                    slot = slot,
                    target = target,
                    selfPackage = context?.packageName.orEmpty(),
                    approvedForPackage = !browser && pkg.isNotEmpty() && repository.isApproved(slot.id, pkg),
                )
            val decision =
                if (browser && decided is FillDecision.NeedsApproval) FillDecision.NeedsApproval(false) else decided
            val refusal =
                when (decision) {
                    is FillDecision.Deny -> {
                        record(slot, pkg, "拒绝：${decision.reason}")
                        "Vault fill refused: ${decision.reason}"
                    }

                    is FillDecision.NeedsApproval -> {
                        askUser(slot, pkg, decision.rememberable)
                    }

                    FillDecision.Allow -> {
                        null
                    }
                }
            if (refusal != null) throw McpToolException.ActionFailed(refusal)
            return decision is FillDecision.NeedsApproval
        }

        /**
         * Polls until [nodeId] is back on screen inside [pkg]. A different app in front means the
         * context changed while the user was deciding, so nothing is written.
         */
        private suspend fun awaitReturn(
            nodeId: String,
            pkg: String,
        ) {
            repeat(RETURN_POLLS) {
                delay(RETURN_POLL_MS)
                val owner = readWindows.read().windows.firstOrNull { containsNode(it.tree, nodeId) }
                if (owner?.packageName == pkg) return
            }
            throw McpToolException.ActionFailed(
                "The input field did not come back after approval; call get_screen_state and retry",
            )
        }

        /**
         * Re-reads the field after approval. The page may have navigated or turned a password
         * input into a text input while the user was deciding: then nothing is written.
         */
        private suspend fun recheck(
            slot: VaultSlot,
            nodeId: String,
            before: FillTarget,
        ): List<WindowData> {
            val (windows, now) = locate(nodeId)
            val selfPackage = accessibilityServiceProvider.getContext()?.packageName.orEmpty()
            val allowed = VaultPolicy.decide(slot, now, selfPackage, approvedForPackage = true) !is FillDecision.Deny
            if (!allowed || now.packageName != before.packageName || now.password != before.password) {
                record(slot, now.packageName.orEmpty(), "审批后输入框已变化，未填写")
                throw McpToolException.ActionFailed("The input field changed after approval; nothing was written")
            }
            return windows
        }

        /** Returns null when the user approved, otherwise the refusal message. */
        private suspend fun askUser(
            slot: VaultSlot,
            pkg: String,
            rememberable: Boolean,
        ): String? {
            val context = accessibilityServiceProvider.getContext()
            val answer = approvals.request(slot.label, pkg, appLabel(context, pkg), slot.sensitive, rememberable)
            if (!answer.approved) {
                record(slot, pkg, "用户未同意")
                return "The user did not approve filling '${slot.label}'"
            }
            if (answer.remember && rememberable) repository.rememberApproval(slot.id, pkg)
            return null
        }

        private suspend fun write(
            slot: VaultSlot,
            pkg: String,
            nodeId: String,
            windows: List<WindowData>,
        ) {
            val value = repository.reveal(slot.id)?.value
            // The executor's error text is never forwarded: keep failures generic.
            val ok = value != null && actionExecutor.setTextOnNode(nodeId, value, windows).isSuccess
            record(slot, pkg, if (ok) "已填写" else "填写失败")
            if (!ok) throw McpToolException.ActionFailed("Could not write '${slot.label}' into node '$nodeId'")
        }

        private suspend fun record(
            slot: VaultSlot,
            pkg: String,
            outcome: String,
        ) {
            repository.recordFill(VaultFillRecord(slot.id, pkg, System.currentTimeMillis(), outcome))
        }

        companion object {
            const val TOOL_LIST = "vault_list_slots"
            const val TOOL_FILL = "vault_fill"
            private const val RETURN_POLLS = 20
            private const val RETURN_POLL_MS = 150L
            private const val TAG = "MCP:VaultTools"
        }
    }

private fun appLabel(
    context: Context?,
    pkg: String,
): String {
    val pm = context?.packageManager ?: return pkg
    return try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        Log.d("MCP:VaultTools", "no label for $pkg: ${e.message}")
        pkg
    }
}

/** True when [pkg] can open web links, i.e. it hosts arbitrary third-party pages. */
private fun isBrowser(
    context: Context?,
    pkg: String,
): Boolean {
    val pm = context?.packageManager ?: return false
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    return pm
        .queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .any { it.activityInfo?.packageName == pkg }
}

private fun containsNode(
    node: AccessibilityNodeData,
    nodeId: String,
): Boolean = node.id == nodeId || node.children.any { containsNode(it, nodeId) }

private fun slotJson(slot: VaultSlot): JsonObject =
    buildJsonObject {
        put("slot", slot.id)
        put("label", slot.label)
        put("category", slot.category.name.lowercase())
        put("sensitive", slot.sensitive)
        if (slot.allowedPackages.isNotEmpty()) {
            put("only_in_apps", buildJsonArray { slot.allowedPackages.forEach { add(it) } })
        }
    }

/**
 * Loads the personal vault and keeps [McpToolUtils.contentRedactor] in sync with it, so vault
 * values are scrubbed from every device-derived tool response from the first request on.
 */
fun bindVaultRedaction(
    scope: CoroutineScope,
    repository: VaultRepository,
): Job =
    scope.launch {
        try {
            repository.load()
        } catch (e: IOException) {
            Log.e("MCP:VaultTools", "Personal vault unavailable: ${e.message}")
            return@launch
        }
        repository.redactor.collect { McpToolUtils.contentRedactor = it }
    }

fun registerVaultTools(
    server: Server,
    tools: VaultTools,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(VaultTools.TOOL_LIST)) {
        server.addTool(
            name = "$toolNamePrefix${VaultTools.TOOL_LIST}",
            description =
                "Lists the user's personal vault slots (phone, name, address, passwords, " +
                    "preferences…) by id and label. Values are never returned: fill them with vault_fill.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { _ -> tools.listSlots() }
    }
    if (perms.isToolEnabled(VaultTools.TOOL_FILL)) {
        server.addTool(
            name = "$toolNamePrefix${VaultTools.TOOL_FILL}",
            description =
                "Writes a personal vault value into an input field without revealing it. You choose " +
                    "the field (node_id from get_screen_state) and the slot; the phone checks the field " +
                    "(passwords only go into password fields, never into this assistant's own chat, " +
                    "optional per-app limits), may ask the user to approve on the phone, then types " +
                    "the value. Later screen reads show it as ‹vault:slot›.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("node_id") {
                                put("type", "string")
                                put("description", "Editable input field's node_id")
                            }
                            putJsonObject("slot") {
                                put("type", "string")
                                put("description", "Vault slot id from vault_list_slots")
                            }
                        },
                    required = listOf("node_id", "slot"),
                ),
        ) { req -> tools.fill(req.arguments) }
    }
}
