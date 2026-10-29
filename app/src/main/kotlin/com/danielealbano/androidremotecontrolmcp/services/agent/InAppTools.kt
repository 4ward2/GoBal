package com.danielealbano.androidremotecontrolmcp.services.agent

import android.content.Context
import android.content.pm.PackageManager
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolRegistrar
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolSetup
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillMatcher
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRepository
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillStep
import com.danielealbano.androidremotecontrolmcp.services.vault.ContentRedactor
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Turns a registered MCP tool into the short description the model is given. Pure. */
object ToolSpecs {
    fun of(registered: RegisteredTool): ToolSpec {
        val tool = registered.tool
        val required = tool.inputSchema.required.orEmpty()
        val params =
            tool.inputSchema.properties
                ?.entries
                .orEmpty()
                .associate { (name, schema) -> marked(name, name in required) to typeOf(schema as? JsonObject) }
        return ToolSpec(tool.name, tool.description.orEmpty(), params)
    }

    /** A parameter that must be given is shown with a star. */
    private fun marked(
        name: String,
        required: Boolean,
    ): String = if (required) "$name*" else name

    private fun typeOf(schema: JsonObject?): String = (schema?.get("type") as? JsonPrimitive)?.contentOrNull ?: "any"
}

/**
 * The phone's tools for the in-app agent. They are the very tools MCP clients reach over the
 * network, registered on a private [Server] that listens on nothing and called directly: the
 * agent needs no HTTP server, no bearer token, no root and no helper process.
 */
@Singleton
class InAppTools
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val registrar: ToolRegistrar,
        private val settings: SettingsRepository,
        private val accessibility: AccessibilityServiceProvider,
        private val skillRepository: SkillRepository,
        private val vault: VaultRepository,
    ) : AgentTools {
        @Volatile
        private var tools: Map<String, RegisteredTool> = emptyMap()

        /**
         * Registers the tools afresh, so the tools the user switched off in the settings since the
         * last task stay off for this one too. Call before each task.
         */
        suspend fun prepare() {
            // Until now only the network server installed the vault's redactor. A phone that never
            // starts that server must still never send a vault value on screen to the model: with
            // the vault unreadable, the task does not start at all.
            vault.load()
            // Looked up at every use: something the user saves while the task runs is hidden from then on too.
            McpToolUtils.contentRedactor = ContentRedactor { text -> vault.redactor.value.redact(text) }
            val config = settings.getServerConfig()
            val capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
            val server = Server(Implementation(SERVER_NAME, BuildConfig.VERSION_NAME), ServerOptions(capabilities))
            // The agent never shares files over the network; links would point at this phone itself.
            val setup = ToolSetup("", config.toolPermissionsConfig, config.fileSizeLimitMb) { LOOPBACK }
            registrar.registerAll(server, setup)
            tools = server.tools
        }

        override fun notReady(): String? = if (accessibility.isReady()) null else NEEDS_ACCESSIBILITY

        override fun specs(): List<ToolSpec> = tools.values.map(ToolSpecs::of)

        override suspend fun call(
            tool: String,
            args: JsonObject,
        ): ToolReply {
            val registered = tools[tool] ?: return ToolReply("No tool named $tool.", isError = true)
            val called = runCatching { registered.handler(CallToolRequest(CallToolRequestParams(tool, args))) }
            val failure = called.exceptionOrNull()
            // Stopping the task cancels the call in flight: that is not a tool error to report.
            if (failure is CancellationException) throw failure
            val result = called.getOrNull()
            return if (result == null) {
                // A tool reports bad arguments and missing permissions by throwing: the model is told.
                ToolReply(failure?.message ?: "the tool failed", isError = true)
            } else {
                val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                ToolReply(text, isError = result.isError == true)
            }
        }

        override fun appName(packageName: String): String =
            try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                packageName
            }

        override fun skills(): List<String> = skillRepository.skills.value.map { it.name }

        override fun skillApps(nameOrRequest: String): List<String> {
            val saved = skillRepository.skills.value
            val named = saved.firstOrNull { it.name == nameOrRequest }
            val skill = named ?: SkillMatcher.match(nameOrRequest, saved)?.skill
            return skill
                ?.steps
                .orEmpty()
                .filterIsInstance<SkillStep.Launch>()
                .map { it.packageName }
        }

        companion object {
            const val NEEDS_ACCESSIBILITY = "还不能操作手机：请先在 系统设置 → 无障碍 里打开本应用的无障碍服务"
            private const val SERVER_NAME = "in-app-agent"
            private const val LOOPBACK = "http://127.0.0.1"
        }
    }
