package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.skill.FileSkillRepository
import com.danielealbano.androidremotecontrolmcp.services.skill.Skill
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillCoordinator
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRunResult
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRunStatus
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRunner
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillSource
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillStep
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("SkillTools")
class SkillToolsTest {
    @TempDir
    lateinit var dir: File

    private val repo by lazy { FileSkillRepository(File(dir, "skills.json"), Dispatchers.Unconfined) }
    private val runner = mockk<SkillRunner>()
    private val tools by lazy { SkillTools(repo, SkillCoordinator(repo, runnerFactory = { runner })) }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun text(result: CallToolResult) = (result.content.first() as TextContent).text

    private val saveArgs =
        """
        {"skill": {"name": "点{{店名}}外卖", "params": [{"name": "店名", "default": "蜜雪冰城"}],
          "steps": [{"type": "launch", "package": "com.sankuai.meituan"},
                    {"type": "tap", "target": {"text": "{{店名}}", "match": "contains"}},
                    {"type": "tap", "target": {"text": "去结算"}}]}}
        """.trimIndent()

    @Test
    fun `save parses the JSON, marks it as agent-made and adds the checkpoint`() =
        runTest {
            tools.save(args(saveArgs))
            val stored = repo.find("点{{店名}}外卖")!!
            assertEquals(SkillSource.AGENT, stored.source)
            assertEquals(SkillStep.Verify("{{店名}}"), stored.steps[2])
            assertEquals(4, stored.steps.size)
        }

    @Test
    fun `save rejects malformed and invalid skills as invalid params`() =
        runTest {
            assertThrows(McpToolException.InvalidParams::class.java) {
                kotlinx.coroutines.runBlocking {
                    tools.save(args("""{"skill": {"name": "x", "steps": [{"type": "fly"}]}}"""))
                }
            }
            assertThrows(McpToolException.InvalidParams::class.java) {
                kotlinx.coroutines.runBlocking { tools.save(args("""{"skill": {"name": "x", "steps": []}}""")) }
            }
            assertThrows(McpToolException.InvalidParams::class.java) {
                kotlinx.coroutines.runBlocking { tools.save(args("""{"replace": true}""")) }
            }
        }

    @Test
    fun `run by name passes params and reports status first`() =
        runTest {
            tools.save(args(saveArgs))
            val given = slot<Map<String, String>>()
            coEvery { runner.run(any(), capture(given)) } answers {
                SkillRunResult(SkillRunStatus.HANDED_OVER, firstArg<Skill>().name, 3, 4, 4, "付款要你自己确认", listOf("1. ok"))
            }
            val out = text(tools.run(args("""{"name": "点{{店名}}外卖", "params": {"店名": "瑞幸"}}""")))
            assertEquals(mapOf("店名" to "瑞幸"), given.captured)
            assertTrue(out.lines()[1] == "status: handed_over", out)
            assertTrue(out.contains("stopped_at: 4"))
        }

    @Test
    fun `run by utterance matches locally and says no_match otherwise`() =
        runTest {
            tools.save(args(saveArgs))
            val given = slot<Map<String, String>>()
            coEvery { runner.run(any(), capture(given)) } answers {
                SkillRunResult(SkillRunStatus.DONE, "x", 4, 4, null, "完成", emptyList())
            }
            assertTrue(text(tools.run(args("""{"utterance": "帮我点瑞幸外卖"}"""))).contains("status: done"))
            assertEquals(mapOf("店名" to "瑞幸"), given.captured)
            assertEquals("status: no_match", text(tools.run(args("""{"utterance": "打开相机"}"""))))
        }

    @Test
    fun `run needs a name or an utterance, and params must be strings`() =
        runTest {
            assertThrows(McpToolException.InvalidParams::class.java) {
                kotlinx.coroutines.runBlocking { tools.run(args("{}")) }
            }
            assertThrows(McpToolException.InvalidParams::class.java) {
                kotlinx.coroutines.runBlocking { tools.run(args("""{"name": "x", "params": {"a": 1}}""")) }
            }
        }

    @Test
    fun `an unknown skill name is a failed run, not a crash`() =
        runTest {
            assertTrue(text(tools.run(args("""{"name": "没有这个"}"""))).contains("status: failed"))
        }

    @Test
    fun `list, get and delete`() =
        runTest {
            assertTrue(text(tools.list()).contains("No skills"))
            tools.save(args(saveArgs))
            assertTrue(text(tools.list()).contains("点{{店名}}外卖 (4 steps, agent, params: 店名=蜜雪冰城)"))
            assertTrue(text(tools.get(args("""{"name": "点{{店名}}外卖"}"""))).contains("核对屏幕上有「{{店名}}」"))
            tools.delete(args("""{"name": "点{{店名}}外卖"}"""))
            assertThrows(McpToolException.InvalidParams::class.java) {
                kotlinx.coroutines.runBlocking { tools.delete(args("""{"name": "点{{店名}}外卖"}""")) }
            }
        }
}
