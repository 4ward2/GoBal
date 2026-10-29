package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.services.skill.ArchiveCut
import com.danielealbano.androidremotecontrolmcp.services.skill.FileSkillRepository
import com.danielealbano.androidremotecontrolmcp.services.skill.NodeSelector
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillCoordinator
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRunResult
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRunStatus
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillScrollDirection
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillStep
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillTestTrees.Node
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillTestTrees.screen
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillTestTrees.window
import com.danielealbano.androidremotecontrolmcp.services.skill.TaskJournal
import com.danielealbano.androidremotecontrolmcp.services.vault.ContentRedactor
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("ActionRecorder")
class ActionRecorderTest {
    @TempDir
    lateinit var dir: File

    private val mt = "com.sankuai.meituan"
    private val repo by lazy { FileSkillRepository(File(dir, "skills.json"), Dispatchers.Unconfined) }
    private val journal by lazy { TaskJournal(repo) }
    private val lastRun = MutableStateFlow<SkillRunResult?>(null)
    private val coordinator = mockk<SkillCoordinator> { every { lastResult } returns lastRun }

    private val searchScreen =
        screen(
            window(
                mt,
                Node(id = "search", text = "搜索", clickable = true, top = 300),
                Node(
                    id = "box",
                    editable = true,
                    resourceId = "$mt:id/search_edit_text",
                    className = "android.widget.EditText",
                    top = 500,
                ),
                Node(id = "blank", clickable = true, top = 900),
            ),
        )

    private val recorder by lazy {
        ActionRecorder(mockk(relaxed = true), mockk(relaxed = true), journal, coordinator).apply {
            readScreen = { searchScreen }
            fieldState = { id -> if (id == "box") "蜜雪冰城" to false else null }
        }
    }

    private fun args(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) -> k to primitive(v) })

    private fun primitive(v: Any) = if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())

    private val ok = CallToolResult(content = listOf(TextContent("ok")))
    private val error = CallToolResult(content = listOf(TextContent("nope")), isError = true)

    @AfterEach
    fun resetRedactor() {
        McpToolUtils.contentRedactor = ContentRedactor.NONE
    }

    private suspend fun doTool(
        tool: String,
        a: JsonObject,
        result: CallToolResult = ok,
    ) = recorder.around(tool, a) { result }

    @Test
    fun `taps by words are recorded as such, detours are left out, and a lookup is marked for reading`() =
        runTest {
            val fromPicture = CallToolResult(content = listOf(TextContent("Tapped, ${TextTapTools.FROM_PICTURE}.")))
            journal.begin("看看拼好饭的饮品")
            doTool("open_app", args("package_id" to mt))
            doTool("tap_text", args("text" to "拼好饭"))
            doTool("tap_text", args("text" to "汉堡披萨"), fromPicture)
            doTool("press_back", args())
            doTool("tap_text", args("text" to "饮品甜点"), fromPicture)
            val listed = journal.summary().lines()
            assertEquals(6, listed.size)
            assertTrue(listed[3].startsWith("3. "))
            val outcome = journal.end(true, null, emptyMap(), ArchiveCut(drop = setOf(3, 4), thenRead = true))
            val skill = checkNotNull(outcome.archived)
            assertTrue(skill.thenRead)
            assertEquals(
                listOf(
                    SkillStep.Launch(mt),
                    SkillStep.Tap(NodeSelector(text = "拼好饭")),
                    SkillStep.Tap(NodeSelector(text = "饮品甜点"), ocr = true),
                ),
                skill.steps,
            )
        }

    @Test
    fun `a long request is saved up to where the reading began, without the app being opened twice`() =
        runTest {
            val request =
                "在美团拼好饭里分别看看「饮品甜点」和「汉堡披萨」两个分类，" +
                    "每个分类往下翻两屏，最后告诉我每个分类最便宜的三样和价格"
            journal.begin(request)
            doTool("open_app", args("package_id" to mt))
            doTool("tap_text", args("text" to "拼好饭"))
            doTool("open_app", args("package_id" to mt))
            doTool("tap_text", args("text" to "饮品甜点"))
            doTool("tap_text", args("text" to "冰粉"))
            doTool("press_back", args())
            val cut = ArchiveCut(thenRead = true, keep = 4)
            val skill = checkNotNull(journal.end(true, null, emptyMap(), cut).archived)
            assertEquals(request, skill.name)
            assertEquals(
                listOf(
                    SkillStep.Launch(mt),
                    SkillStep.Tap(NodeSelector(text = "拼好饭")),
                    SkillStep.Tap(NodeSelector(text = "饮品甜点")),
                ),
                skill.steps,
            )
        }

    @Test
    fun `the way back to an earlier point of a task can be replayed, and going back drops what came after`() =
        runTest {
            journal.begin("看看拼好饭")
            doTool("tap_text", args("text" to "拼好饭"))
            doTool("tap_text", args("text" to "饮品甜点"))
            doTool("tap_text", args("text" to "冰粉"))
            assertEquals(
                listOf(SkillStep.Tap(NodeSelector(text = "拼好饭")), SkillStep.Tap(NodeSelector(text = "饮品甜点"))),
                journal.wayBack(2).route,
            )
            journal.rewind(2)
            assertTrue(journal.summary().startsWith("goal: 看看拼好饭; steps: 2"))
            assertTrue(journal.wayBack(0).route.isEmpty())
            doTool("tap_text", args("text" to "冰粉"))
            assertEquals(listOf<SkillStep>(SkillStep.Tap(NodeSelector(text = "冰粉"))), journal.wayBack(2).since)
            // Opening the app at its first screen starts the way over.
            doTool("open_app", args("package_id" to mt, "fresh" to "true"))
            assertTrue(journal.summary().startsWith("goal: 看看拼好饭; steps: 1"))
        }

    @Test
    fun `a task done with tools is archived and replays by its name`() =
        runTest {
            journal.begin("美团搜蜜雪冰城")
            doTool("open_app", args("package_id" to mt))
            doTool("click_node", args("node_id" to "search"))
            doTool("type_append_text", args("node_id" to "box", "text" to "蜜雪"))
            doTool("scroll", args("direction" to "down"))
            doTool("get_screen_state", args())
            val outcome = journal.end(true, null, mapOf("店名" to "蜜雪冰城"))
            val skill = checkNotNull(outcome.archived)
            assertEquals("美团搜{{店名}}", skill.name)
            // The input box has an id but no words: it is found again by its id.
            val box = NodeSelector(resourceId = "$mt:id/search_edit_text", packageName = mt)
            assertEquals(
                listOf(
                    SkillStep.Launch(mt),
                    SkillStep.Tap(NodeSelector(text = "搜索", className = "android.widget.TextView", packageName = mt)),
                    SkillStep.Type(box, "{{店名}}"),
                    SkillStep.Scroll(SkillScrollDirection.DOWN),
                ),
                skill.steps,
            )
        }

    @Test
    fun `a failed tool call is not recorded`() =
        runTest {
            journal.begin("x")
            doTool("click_node", args("node_id" to "search"), error)
            assertTrue(journal.isEmpty)
        }

    @Test
    fun `unlabelled taps, password fields, private data and long presses block the archive`() =
        runTest {
            journal.begin("x")
            doTool("click_node", args("node_id" to "blank"))
            assertTrue(journal.summary().contains("cannot archive"))

            journal.begin("x")
            recorder.fieldState = { "secret" to true }
            doTool("type_append_text", args("node_id" to "box", "text" to "secret"))
            assertTrue(journal.summary().contains("密码框"))

            journal.begin("x")
            recorder.fieldState = { "13800138000" to false }
            McpToolUtils.contentRedactor = ContentRedactor { it.replace("13800138000", "‹vault:phone›") }
            doTool("type_append_text", args("node_id" to "box", "text" to "13800138000"))
            assertTrue(journal.summary().contains("保险箱"))

            journal.begin("x")
            doTool("long_press", args("x" to 1, "y" to 1))
            assertTrue(journal.summary().contains("long_press"))
        }

    @Test
    fun `going home first is setup, going home later cannot be replayed`() =
        runTest {
            journal.begin("x")
            doTool("press_home", args())
            assertTrue(journal.isEmpty)
            doTool("press_back", args())
            doTool("press_home", args())
            assertTrue(journal.summary().contains("press_home"))
        }

    @Test
    fun `a skill that ran contributes its executed steps, a no_match adds nothing`() =
        runTest {
            journal.begin("x")
            lastRun.value =
                SkillRunResult(
                    status = SkillRunStatus.FAILED,
                    skillName = "s",
                    completedSteps = 1,
                    totalSteps = 3,
                    stoppedAt = 2,
                    message = "找不到",
                    log = emptyList(),
                    executed = listOf(SkillStep.Launch(mt)),
                )
            val noMatch = CallToolResult(content = listOf(TextContent("status: no_match")))
            doTool("skill_run", args("utterance" to "x"), noMatch)
            assertTrue(journal.isEmpty)
            val failed = CallToolResult(content = listOf(TextContent("status: failed")))
            doTool("skill_run", args("utterance" to "x"), failed)
            assertTrue(journal.summary().contains("steps: 1"))
            assertNull(journal.end(true, null, emptyMap()).archived)
        }

    @Test
    fun `a broken skill's good steps plus the model's repair are archived under the same name`() =
        runTest {
            val settings = "com.android.settings"
            lastRun.value =
                SkillRunResult(
                    status = SkillRunStatus.FAILED,
                    skillName = "设置里搜{{关键词}}",
                    completedSteps = 1,
                    totalSteps = 3,
                    stoppedAt = 2,
                    message = "找不到",
                    log = emptyList(),
                    executed = listOf(SkillStep.Launch(settings)),
                )
            val failed = CallToolResult(content = listOf(TextContent("status: failed")))
            doTool("skill_run", args("utterance" to "设置里搜WLAN"), failed)
            // The model follows the instructions: task_begin with the same request keeps the replayed steps.
            journal.begin("设置里搜WLAN")
            doTool("click_node", args("node_id" to "search"))
            recorder.fieldState = { "WLAN" to false }
            doTool("type_append_text", args("node_id" to "box", "text" to "WLAN"))
            val repaired = checkNotNull(journal.end(true, null, mapOf("关键词" to "WLAN")).archived)
            assertEquals("设置里搜{{关键词}}", repaired.name)
            assertEquals(SkillStep.Launch(settings), repaired.steps.first())
            assertEquals(3, repaired.steps.size)
        }
}
