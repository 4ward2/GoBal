package com.danielealbano.androidremotecontrolmcp.services.screencapture

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import com.danielealbano.androidremotecontrolmcp.services.vault.ContentRedactor
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultRedactor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("VaultScreenshotMask")
class VaultScreenshotMaskTest {
    private val redactor = VaultRedactor(mapOf("phone" to "13800138000"))

    private fun node(
        text: String? = null,
        desc: String? = null,
        visible: Boolean = true,
        top: Int = 0,
        children: List<AccessibilityNodeData> = emptyList(),
    ) = AccessibilityNodeData(
        id = "n$top-${text.orEmpty()}-${desc.orEmpty()}",
        text = text,
        contentDescription = desc,
        bounds = BoundsData(0, top, 100, top + 10),
        visible = visible,
        children = children,
    )

    private fun window(tree: AccessibilityNodeData) = WindowData(windowId = 1, windowType = "APPLICATION", tree = tree)

    @Test
    fun `only the innermost matching node is masked`() {
        val field = node(text = "138 0013 8000", top = 50)
        val page = node(desc = "已填写：13800138000", children = listOf(field))
        val masked = VaultScreenshotMask.secretBounds(listOf(window(page)), redactor)
        assertEquals(listOf(BoundsData(0, 50, 100, 60)), masked)
    }

    @Test
    fun `parent is masked when no child carries the value`() {
        val page = node(desc = "已填写：13800138000", children = listOf(node(text = "备注")))
        val masked = VaultScreenshotMask.secretBounds(listOf(window(page)), redactor)
        assertEquals(listOf(BoundsData(0, 0, 100, 10)), masked)
    }

    @Test
    fun `invisible nodes are not masked`() {
        val hidden = node(text = "13800138000", visible = false)
        val root = node(children = listOf(hidden))
        assertTrue(VaultScreenshotMask.secretBounds(listOf(window(root)), redactor).isEmpty())
    }

    @Test
    fun `nothing is masked without vault values`() {
        val field = node(text = "13800138000")
        assertTrue(VaultScreenshotMask.secretBounds(listOf(window(field)), ContentRedactor.NONE).isEmpty())
    }
}
