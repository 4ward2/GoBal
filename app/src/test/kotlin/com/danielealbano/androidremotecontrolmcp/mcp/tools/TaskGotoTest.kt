package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.skill.NodeSelector
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillCoordinator
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillStep
import com.danielealbano.androidremotecontrolmcp.services.skill.TaskJournal
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("task_goto")
class TaskGotoTest {
    private val journal = TaskJournal(mockk(relaxed = true))
    private val coordinator = mockk<SkillCoordinator>(relaxed = true)
    private val executor = mockk<ActionExecutor>()
    private val tools = TaskTools(journal, coordinator, executor)

    private fun text(result: CallToolResult) = (result.content.single() as TextContent).text

    /** The list, then an item tapped twice because the first tap did nothing: two taps, one screen. */
    private fun twoTapsOneScreen() {
        journal.begin("看看拼好饭")
        journal.record(SkillStep.Tap(NodeSelector(text = "饮品甜点")))
        journal.record(SkillStep.Tap(NodeSelector(text = "冰粉")))
        journal.record(SkillStep.Tap(NodeSelector(text = "冰粉 ¥1.9")))
        coEvery { executor.pressBack() } returns Result.success(Unit)
    }

    @Test
    fun `the number of screens the caller knows of is pressed back, and the journal goes back to the step`() =
        runTest {
            twoTapsOneScreen()
            val result =
                tools.goto(
                    buildJsonObject {
                        put("step", 1)
                        put("back", 1)
                    },
                )
            assertEquals("status: done\nwent back 1 screen(s)", text(result))
            coVerify(exactly = 1) { executor.pressBack() }
            assertTrue(journal.summary().contains("steps: 1"))
        }

    @Test
    fun `without it one press a tap is made, and a count out of range is not taken`() =
        runTest {
            twoTapsOneScreen()
            val result =
                tools.goto(
                    buildJsonObject {
                        put("step", 1)
                        put("back", 9)
                    },
                )
            assertEquals("status: done\nwent back 2 screen(s)", text(result))
            coVerify(exactly = 2) { executor.pressBack() }
        }
}
