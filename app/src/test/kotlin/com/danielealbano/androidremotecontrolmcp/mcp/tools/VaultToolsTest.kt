package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CachedNode
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import com.danielealbano.androidremotecontrolmcp.services.vault.FileVaultRepository
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultApprovalResult
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultCategory
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultCipher
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("VaultTools")
class VaultToolsTest {
    private object PlainCipher : VaultCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(blob: ByteArray) = blob
    }

    @TempDir
    lateinit var dir: File

    private lateinit var repo: FileVaultRepository
    private lateinit var approvals: VaultApprovalCoordinator
    private lateinit var executor: ActionExecutor
    private lateinit var nodeCache: AccessibilityNodeCache
    private lateinit var provider: AccessibilityServiceProvider
    private lateinit var realNode: AccessibilityNodeInfo
    private lateinit var tools: VaultTools
    private lateinit var packageManager: PackageManager

    private val field =
        AccessibilityNodeData(id = "node_field", bounds = BoundsData(0, 0, 10, 10), editable = true, enabled = true)
    private var windowPackage = "com.taobao.taobao"

    @BeforeEach
    fun setUp() =
        runTest {
            repo = FileVaultRepository(dir, PlainCipher, Dispatchers.Unconfined)
            repo.upsert(VaultEntry("phone", "手机号", VaultCategory.CONTACT, "13800138000", sensitive = false))
            repo.upsert(VaultEntry("password", "登录密码", VaultCategory.CREDENTIAL, "s3cret-Pa55", sensitive = true))
            approvals = mockk()
            executor = mockk()
            coEvery { executor.setTextOnNode(any(), any(), any()) } returns Result.success(Unit)
            realNode = mockk(relaxed = true)
            every { realNode.isEditable } returns true
            every { realNode.isEnabled } returns true
            every { realNode.isPassword } returns false
            nodeCache = mockk()
            every { nodeCache.get("node_field") } returns CachedNode(realNode, 1, 0, "root")
            packageManager = mockk(relaxed = true)
            every { packageManager.queryIntentActivities(any(), any<Int>()) } returns emptyList()
            val context = mockk<Context>(relaxed = true)
            every { context.packageName } returns "com.example.assistant"
            every { context.packageManager } returns packageManager
            provider = mockk(relaxed = true)
            every { provider.getContext() } returns context
            tools = VaultTools(repo, approvals, mockk(), provider, nodeCache, executor)
            tools.readWindows = FreshWindowReader { MultiWindowResult(listOf(appWindow())) }
        }

    private fun appWindow() =
        WindowData(
            windowId = 1,
            windowType = "APPLICATION",
            packageName = windowPackage,
            tree =
                AccessibilityNodeData(
                    id = "root",
                    bounds = BoundsData(0, 0, 10, 10),
                    children = listOf(field),
                ),
        )

    private fun lastOutcome(): String {
        val log = repo.fillLog.value
        return log.first().outcome
    }

    private fun args(slot: String) =
        buildJsonObject {
            put("node_id", "node_field")
            put("slot", slot)
        }

    private fun text(r: CallToolResult) = (r.content.single() as TextContent).text

    @Test
    fun `list shows labels and never values`() =
        runTest {
            val out = text(tools.listSlots())
            assertTrue(out.contains("手机号"))
            assertTrue(out.contains("\"password\""))
            assertFalse(out.contains("13800138000"))
            assertFalse(out.contains("s3cret"))
        }

    @Test
    fun `first fill asks, remembers, and the second fill does not ask`() =
        runTest {
            coEvery {
                approvals.request(any(), any(), any(), any(), any())
            } returns VaultApprovalResult(true, remember = true)
            val out = text(tools.fill(args("phone")))
            assertFalse(out.contains("13800138000"))
            coVerify { executor.setTextOnNode("node_field", "13800138000", any()) }
            tools.fill(args("phone"))
            coVerify(exactly = 1) { approvals.request(any(), any(), any(), any(), any()) }
            assertEquals("已填写", lastOutcome())
        }

    @Test
    fun `declined approval does not type anything`() =
        runTest {
            coEvery { approvals.request(any(), any(), any(), any(), any()) } returns VaultApprovalResult(false, false)
            assertThrows<McpToolException.ActionFailed> { tools.fill(args("phone")) }
            coVerify(exactly = 0) { executor.setTextOnNode(any(), any(), any()) }
            assertEquals("用户未同意", lastOutcome())
        }

    @Test
    fun `password into a non password field is refused before asking the user`() =
        runTest {
            val e = assertThrows<McpToolException.ActionFailed> { tools.fill(args("password")) }
            assertFalse(e.message.orEmpty().contains("s3cret"))
            coVerify(exactly = 0) { approvals.request(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { executor.setTextOnNode(any(), any(), any()) }
        }

    @Test
    fun `password into a password field asks every time`() =
        runTest {
            every { realNode.isPassword } returns true
            coEvery {
                approvals.request(any(), any(), any(), any(), any())
            } returns VaultApprovalResult(true, remember = true)
            tools.fill(args("password"))
            tools.fill(args("password"))
            coVerify(exactly = 2) { approvals.request("登录密码", "com.taobao.taobao", any(), true, false) }
            coVerify { executor.setTextOnNode("node_field", "s3cret-Pa55", any()) }
        }

    @Test
    fun `filling into the assistant itself is refused`() =
        runTest {
            windowPackage = "com.example.assistant"
            assertThrows<McpToolException.ActionFailed> { tools.fill(args("phone")) }
            coVerify(exactly = 0) { executor.setTextOnNode(any(), any(), any()) }
        }

    @Test
    fun `unknown slot and unknown node are rejected`() =
        runTest {
            assertThrows<McpToolException.InvalidParams> { tools.fill(args("nope")) }
            val missingNode =
                buildJsonObject {
                    put("node_id", "node_missing")
                    put("slot", "phone")
                }
            assertThrows<McpToolException.NodeNotFound> { tools.fill(missingNode) }
        }

    @Test
    fun `executor failure is reported without the value`() =
        runTest {
            coEvery { approvals.request(any(), any(), any(), any(), any()) } returns VaultApprovalResult(true, false)
            coEvery {
                executor.setTextOnNode(any(), any(), any())
            } returns Result.failure(RuntimeException("set text 13800138000"))
            val e = assertThrows<McpToolException.ActionFailed> { tools.fill(args("phone")) }
            assertFalse(e.message.orEmpty().contains("13800138000"))
        }

    @Test
    fun `in a browser every fill asks and cannot be remembered`() =
        runTest {
            val browser = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = windowPackage } }
            every { packageManager.queryIntentActivities(any(), any<Int>()) } returns listOf(browser)
            coEvery {
                approvals.request(any(), any(), any(), any(), any())
            } returns VaultApprovalResult(true, remember = true)
            tools.fill(args("phone"))
            tools.fill(args("phone"))
            coVerify(exactly = 2) { approvals.request("手机号", windowPackage, any(), false, false) }
            assertFalse(repo.isApproved("phone", windowPackage))
        }

    @Test
    fun `nothing is written when the field stops being a password field during approval`() =
        runTest {
            every { realNode.isPassword } returnsMany listOf(true, false)
            coEvery { approvals.request(any(), any(), any(), any(), any()) } returns VaultApprovalResult(true, false)
            assertThrows<McpToolException.ActionFailed> { tools.fill(args("password")) }
            coVerify(exactly = 0) { executor.setTextOnNode(any(), any(), any()) }
        }
}
