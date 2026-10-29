package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.skill.OcrHit
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillTestTrees.Node
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillTestTrees.screen
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillTestTrees.window
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("tap_text")
class TextTapToolsTest {
    private val taps = mutableListOf<Pair<Float, Float>>()
    private val asked = mutableListOf<String>()
    private var inPicture: OcrHit? = null

    /** A tab bar that reaches the tree as one node, as web pages do, and one ordinary button. */
    private val page =
        screen(
            window(
                "com.sankuai.meituan",
                Node(id = "tabs", text = "推荐 炒菜盖饭 米粉面条 汉堡披萨 饮品甜点", clickable = true, top = 300),
                Node(id = "search", text = "搜索", clickable = true, top = 600),
            ),
        )

    private val tools =
        TextTapTools(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)).apply {
            readWindows = { page.windows }
            locate = { text ->
                asked += text
                inPicture
            }
            tapAt = { x, y ->
                taps += x to y
                Result.success(Unit)
            }
        }

    private suspend fun tap(text: String): String {
        val result = tools.tap(JsonObject(mapOf("text" to JsonPrimitive(text))))
        return (result.content.single() as TextContent).text
    }

    @Test
    fun `a control that says exactly the words is tapped without reading a picture`() =
        runTest {
            val said = tap("搜索")
            assertTrue(said.endsWith(TextTapTools.FROM_CONTROL + "."))
            assertEquals(1, taps.size)
            assertTrue(asked.isEmpty())
        }

    @Test
    fun `words that are only part of a merged node are found in the picture and tapped there`() =
        runTest {
            inPicture = OcrHit(930f, 640f, "饮品甜点")
            val said = tap("饮品甜点")
            assertTrue(said.endsWith(TextTapTools.FROM_PICTURE + "."))
            assertEquals(listOf(930f to 640f), taps)
            assertEquals(listOf("饮品甜点"), asked)
        }

    @Test
    fun `words that are nowhere on the screen tap nothing and say so`() =
        runTest {
            val refused = assertThrows<McpToolException.ActionFailed> { tap("麻辣烫") }
            assertTrue(refused.message.orEmpty().contains("is not on the screen"))
            assertTrue(taps.isEmpty())
        }

    @Test
    fun `a pay button is never tapped, whether asked for by name or found under other words`() =
        runTest {
            assertThrows<McpToolException.ActionFailed> { tap("提交订单") }
            assertTrue(asked.isEmpty())
            inPicture = OcrHit(700f, 2900f, "去支付 ¥12.8")
            assertThrows<McpToolException.ActionFailed> { tap("¥12.8") }
            assertTrue(taps.isEmpty())
        }

    @Test
    fun `blank words are refused`() =
        runTest {
            assertThrows<McpToolException.InvalidParams> { tap("  ") }
        }
}
