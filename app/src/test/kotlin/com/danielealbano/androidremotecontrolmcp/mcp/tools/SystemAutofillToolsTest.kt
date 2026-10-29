package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("SystemAutofillTools")
class SystemAutofillToolsTest {
    private lateinit var executor: ActionExecutor
    private lateinit var tools: SystemAutofillTools

    private val bounds = BoundsData(0, 0, 10, 10)
    private val field = AccessibilityNodeData(id = "node_pwd", bounds = bounds, editable = true, enabled = true)
    private val label = AccessibilityNodeData(id = "node_label", text = "密码", bounds = bounds)

    @BeforeEach
    fun setUp() {
        executor = mockk()
        coEvery { executor.tap(any(), any()) } returns Result.success(Unit)
        tools = SystemAutofillTools(mockk(), executor, mockk(relaxed = true))
        tools.activeVault = { "com.example.vault/.VaultService" }
        tools.readWindows =
            FreshWindowReader {
                val root = AccessibilityNodeData(id = "root", bounds = bounds, children = listOf(label, field))
                val window = WindowData(windowId = 1, windowType = "APPLICATION", packageName = "a.b", tree = root)
                MultiWindowResult(listOf(window))
            }
    }

    private fun args(node: String) = buildJsonObject { put("node_id", node) }

    @Test
    fun `focuses the field and names the vault without typing anything`() =
        runTest {
            val text = (tools.requestFill(args("node_pwd")).content.single() as TextContent).text
            assertTrue(text.contains("com.example.vault/.VaultService"))
            coVerify(exactly = 1) { executor.tap(5f, 5f) }
            coVerify(exactly = 0) { executor.setTextOnNode(any(), any(), any()) }
        }

    @Test
    fun `fails clearly when no system vault is enabled`() =
        runTest {
            tools.activeVault = { null }
            assertThrows<McpToolException.ActionFailed> { tools.requestFill(args("node_pwd")) }
            coVerify(exactly = 0) { executor.tap(any(), any()) }
        }

    @Test
    fun `rejects unknown and non editable nodes`() =
        runTest {
            assertThrows<McpToolException.NodeNotFound> { tools.requestFill(args("node_missing")) }
            assertThrows<McpToolException.InvalidParams> { tools.requestFill(args("node_label")) }
            coVerify(exactly = 0) { executor.tap(any(), any()) }
        }

    @Test
    fun `focus failure is reported`() =
        runTest {
            coEvery { executor.tap(any(), any()) } returns Result.failure(IllegalStateException("x"))
            assertThrows<McpToolException.ActionFailed> { tools.requestFill(args("node_pwd")) }
        }
}
