package com.danielealbano.androidremotecontrolmcp.services.agent

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Reads what the real formatter writes, so the two cannot drift apart unnoticed. */
@DisplayName("ScreenText")
class ScreenTextTest {
    private val bounds = BoundsData(0, 0, 10, 10)

    private fun leaf(
        id: String,
        text: String,
    ) = AccessibilityNodeData(id = id, text = text, bounds = bounds, visible = true)

    private fun window(
        id: Int,
        type: String,
        app: String,
        child: AccessibilityNodeData,
    ): WindowData {
        val inside = listOf(child)
        val root = AccessibilityNodeData(id = "r$id", bounds = bounds, clickable = true, children = inside)
        // The title is the app's own text: it must not be able to pass for a field of the header.
        return WindowData(id, type, app, "t focused:true", layer = id, focused = app == "com.eg.shop", tree = root)
    }

    private val text: String =
        CompactTreeFormatter().formatMultiWindow(
            MultiWindowResult(
                windows =
                    listOf(
                        window(1, "SYSTEM", "com.android.systemui", leaf("s1", "12:00")),
                        window(2, "APPLICATION", "com.eg.shop", leaf("a1", "订单详情")),
                        window(3, "APPLICATION", "com.eg.pay", leaf("b1", "去支付")),
                    ),
            ),
            ScreenInfo(1080, 2400, 420, "portrait"),
        )

    @Test
    fun `every window's controls are read, not only the first one's`() {
        val screen = ScreenText.parse(text)
        assertEquals("12:00", screen.labels["s1"])
        assertEquals("订单详情", screen.labels["a1"])
        assertEquals("去支付", screen.wordsWithin("r3"))
        assertTrue(screen.has("r3"))
        assertFalse(screen.has("zz"))
        assertTrue(screen.allText.contains("去支付"))
    }

    @Test
    fun `the app in front is the focused app window, whatever a window title claims`() {
        assertEquals("com.eg.shop", ScreenText.parse(text).packageName)
    }

    @Test
    fun `a further page adds to the screen and carries the next cursor`() {
        val note = "note:more nodes available — call get_screen_state with cursor \"k.2\" to continue."
        val first = ScreenText.parse(text + "\n" + note)
        assertEquals("k.2", first.nextCursor)
        val both = first + ScreenText.parse(text.replace("b1", "c1").replace("去支付", "提交订单"))
        assertEquals("", both.nextCursor)
        assertEquals("提交订单", both.labels["c1"])
        assertEquals("去支付", both.labels["b1"])
        assertTrue(both.wordsWithin("r3").contains("提交订单"))
    }

    @Test
    fun `a result that is no screen at all knows no control`() {
        val nothing = ScreenText.parse("note:invalid cursor — call get_screen_state without a cursor")
        assertFalse(nothing.has("a1"))
        assertEquals("", nothing.packageName)
    }
}
