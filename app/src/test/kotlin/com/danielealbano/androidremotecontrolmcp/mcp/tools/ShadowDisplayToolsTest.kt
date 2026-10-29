package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TargetDisplay
import com.danielealbano.androidremotecontrolmcp.services.shadow.ShadowDisplayClient
import com.danielealbano.androidremotecontrolmcp.services.shadow.ShadowDisplayException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("ShadowDisplayTools")
class ShadowDisplayToolsTest {
    private lateinit var client: ShadowDisplayClient
    private lateinit var tools: ShadowDisplayTools

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private companion object {
        const val TAOBAO = "com.taobao.taobao"
    }

    private fun ok(extra: String = "") = Result.success(json("""{"ok":true$extra}"""))

    private fun statusWith(vararg ids: Int) =
        Result.success(json("""{"ok":true,"displays":[${ids.joinToString(",") { """{"display_id":$it}""" }}]}"""))

    @BeforeEach
    fun setUp() {
        TargetDisplay.reset()
        client = mockk()
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "com.example.assistant"
        val provider = mockk<AccessibilityServiceProvider>(relaxed = true)
        every { provider.getContext() } returns context
        tools = ShadowDisplayTools(client, provider)
        coEvery { client.request("open", any()) } returns ok(""","component":"a.b/.Main"""")
        coEvery { client.request("destroy", any()) } returns ok()
        coEvery { client.request("to_main", any()) } returns ok()
        coEvery { client.request("resize", any()) } returns ok()
    }

    @AfterEach
    fun tearDown() {
        TargetDisplay.reset()
    }

    @Test
    fun `open creates a display when none exists and targets it`() =
        runTest {
            coEvery { client.request("status", any()) } returns statusWith()
            coEvery { client.request("create", any()) } returns
                Result.success(json("""{"ok":true,"display":{"display_id":9}}"""))
            val r = tools.openApp(buildJsonObject { put("package_id", "com.taobao.taobao") })
            assertEquals(9, TargetDisplay.id)
            assertTrue((r.content[0] as TextContent).text.contains("shadow display 9"))
            coVerify {
                client.request(
                    "open",
                    match { it["display_id"] == JsonPrimitive(9) && it["package"] == JsonPrimitive(TAOBAO) },
                )
            }
        }

    @Test
    fun `open reuses an existing display`() =
        runTest {
            coEvery { client.request("status", any()) } returns statusWith(4)
            tools.openApp(buildJsonObject { put("package_id", "com.taobao.taobao") })
            assertEquals(4, TargetDisplay.id)
            coVerify(exactly = 0) { client.request("create", any()) }
        }

    @Test
    fun `open passes a deep link to the daemon`() =
        runTest {
            coEvery { client.request("status", any()) } returns statusWith(4)
            tools.openApp(
                buildJsonObject {
                    put("package_id", "com.sankuai.meituan")
                    put("uri", "imeituan://www.meituan.com/search?q=x")
                },
            )
            coVerify {
                client.request("open", match { it["uri"] == JsonPrimitive("imeituan://www.meituan.com/search?q=x") })
            }
        }

    @Test
    fun `open rejects a uri with spaces or quotes`() =
        runTest {
            assertThrows<McpToolException.InvalidParams> {
                tools.openApp(
                    buildJsonObject {
                        put("package_id", "a.b")
                        put("uri", "https://x.y/a b")
                    },
                )
            }
            assertThrows<McpToolException.InvalidParams> {
                tools.openApp(
                    buildJsonObject {
                        put("package_id", "a.b")
                        put("uri", "https://x.y/'")
                    },
                )
            }
        }

    @Test
    fun `the assistant itself cannot be opened on a shadow display`() =
        runTest {
            assertThrows<McpToolException.InvalidParams> {
                tools.openApp(buildJsonObject { put("package_id", "com.example.assistant") })
            }
            coVerify(exactly = 0) { client.request(any(), any()) }
        }

    @Test
    fun `open rejects an invalid package`() =
        runTest {
            assertThrows<McpToolException.InvalidParams> {
                tools.openApp(buildJsonObject { put("package_id", "rm -rf /") })
            }
        }

    @Test
    fun `open rejects an oversize display`() =
        runTest {
            assertThrows<McpToolException.InvalidParams> {
                tools.openApp(
                    buildJsonObject {
                        put("package_id", "a.b")
                        put("width", 1440)
                        put("height", 16000)
                    },
                )
            }
        }

    @Test
    fun `daemon failure becomes ActionFailed and keeps target`() =
        runTest {
            coEvery { client.request("status", any()) } returns Result.failure(ShadowDisplayException("not running"))
            assertThrows<McpToolException.ActionFailed> {
                tools.openApp(buildJsonObject { put("package_id", "a.b") })
            }
            assertTrue(TargetDisplay.isDefault)
        }

    @Test
    fun `resize requires a shadow target`() =
        runTest {
            assertThrows<McpToolException.ActionFailed> { tools.resize(buildJsonObject { put("height", 9000) }) }
            TargetDisplay.set(5)
            tools.resize(buildJsonObject { put("height", 9000) })
            coVerify {
                client.request(
                    "resize",
                    match { it["display_id"] == JsonPrimitive(5) && it["height"] == JsonPrimitive(9000) },
                )
            }
        }

    @Test
    fun `hand over and close reset the target`() =
        runTest {
            TargetDisplay.set(5)
            tools.handOver(buildJsonObject { put("package_id", "a.b") })
            assertTrue(TargetDisplay.isDefault)
            TargetDisplay.set(5)
            coEvery { client.request("status", any()) } returns statusWith(5)
            tools.close()
            assertTrue(TargetDisplay.isDefault)
            coVerify { client.request("destroy", match { it["display_id"] == JsonPrimitive(5) }) }
        }

    @Test
    fun `set target accepts zero and open shadow displays only`() =
        runTest {
            coEvery { client.request("status", any()) } returns statusWith(6)
            tools.setTarget(buildJsonObject { put("display_id", 6) })
            assertEquals(6, TargetDisplay.id)
            assertThrows<McpToolException.InvalidParams> { tools.setTarget(buildJsonObject { put("display_id", 3) }) }
            tools.setTarget(buildJsonObject { put("display_id", 0) })
            assertTrue(TargetDisplay.isDefault)
        }
}
