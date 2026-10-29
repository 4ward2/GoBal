package com.danielealbano.androidremotecontrolmcp.services.agent

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The probe on a screen written by the real formatter: a page, and a pay sheet in a window of its own. */
@DisplayName("GuardProbe")
class GuardProbeTest {
    private val bounds = BoundsData(0, 0, 10, 10)
    private val app = "com.phonemcp.guardtest"

    private fun leaf(
        id: String,
        text: String,
    ) = AccessibilityNodeData(id = id, text = text, bounds = bounds, visible = true)

    private fun window(
        id: Int,
        inside: AccessibilityNodeData,
    ): WindowData {
        val root = AccessibilityNodeData(id = "r$id", bounds = bounds, children = listOf(inside))
        return WindowData(id, "APPLICATION", app, "t", layer = id, focused = id == 2, tree = root)
    }

    /** The button says nothing itself; its words are on a child. */
    private val bareButton =
        AccessibilityNodeData(id = "btn", bounds = bounds, clickable = true, children = listOf(leaf("words", "提交订单")))

    private val screen: String =
        CompactTreeFormatter().formatMultiWindow(
            MultiWindowResult(windows = listOf(window(1, leaf("more", "查看详情")), window(2, bareButton))),
            ScreenInfo(1080, 2400, 420, "portrait"),
        )

    private class Phone(
        private val screen: String,
    ) : AgentTools {
        val called = mutableListOf<String>()

        override fun notReady(): String? = null

        override fun specs(): List<ToolSpec> = PhoneAgent.OFFERED.map { ToolSpec(it, "") }

        override suspend fun call(
            tool: String,
            args: JsonObject,
        ): ToolReply {
            called += tool
            return ToolReply(if (tool == Eyes.GET_SCREEN) screen else "status: no_match")
        }

        override fun appName(packageName: String): String = packageName

        override fun skills(): List<String> = emptyList()

        override fun skillApps(nameOrRequest: String): List<String> = emptyList()
    }

    @Test
    fun `judging lists the pay button and its bare container as stopped, and presses nothing`() =
        runTest {
            val phone = Phone(screen)
            val lines = GuardProbe(phone).judge("提交订单")
            assertTrue(lines.any { it == "app in front: $app" })
            assertTrue(lines.any { it.startsWith("  btn -> STOPPED") })
            assertTrue(lines.any { it.startsWith("  words -> STOPPED") })
            assertFalse(lines.any { it.contains("more ->") })
            assertEquals(setOf(Eyes.GET_SCREEN), phone.called.toSet())
        }

    @Test
    fun `the scripted loop is stopped at the container of the words too, before any tap`() =
        runTest {
            listOf(0, 1).forEach { up ->
                val phone = Phone(screen)
                val outcome = GuardProbe(phone).loop(ProbeScript("提交订单", up), app)
                assertEquals(RunStatus.HANDED_OVER, outcome.status)
                assertFalse("click_node" in phone.called)
            }
        }

    @Test
    fun `a harmless button is tapped, and the loop says so`() =
        runTest {
            val phone = Phone(screen)
            val outcome = GuardProbe(phone).loop(ProbeScript("查看详情", 0), app)
            assertTrue("click_node" in phone.called)
            assertEquals(ProbeScript.NOT_STOPPED, outcome.result)
        }

    @Test
    fun `the scripted loop presses nothing in any other app`() =
        runTest {
            val phone = Phone(screen)
            val outcome = GuardProbe(phone).loop(ProbeScript("查看详情", 0), "com.another.app")
            assertEquals(RunStatus.STOPPED, outcome.status)
            assertFalse("click_node" in phone.called)
        }
}
