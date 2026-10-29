package com.danielealbano.androidremotecontrolmcp.services.agent

import com.danielealbano.androidremotecontrolmcp.services.chat.ChatApiResult
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The plan as the task's record keeps it: made first, each step archived with what it achieved. */
@DisplayName("PhoneAgent plan record")
class PlanRecordTest {
    private val phone = FakePhone()
    private val events = Recorder()
    private val asked = mutableListOf<List<ChatMessage>>()
    private val replies = ArrayDeque<ChatApiResult>()

    private fun says(vararg json: String) = json.forEach { replies += ChatApiResult(ok = true, content = it) }

    private suspend fun run(goal: String = "看看拼好饭"): AgentOutcome {
        val brain =
            AgentBrain { messages ->
                asked += messages
                replies.removeFirstOrNull() ?: ChatApiResult(ok = true, content = look)
            }
        val gate =
            object : AppGate {
                override fun settled(packageName: String): Boolean = true

                override suspend fun ask(packageName: String): Boolean = true
            }
        return PhoneAgent(phone, brain, gate, events, pause = {}, planFirst = true).run(goal)
    }

    private val look = """{"action":"get_screen_state","args":{}}"""
    private val done = """{"action":"done","args":{"ok":true,"summary":"做完了"}}"""
    private val plan = """{"action":"plan","args":{"steps":["进入饮品甜点","点开冰粉","记下套餐"]}}"""

    private fun tap(
        words: String,
        step: Int,
        outcome: String = "",
    ) = """{"action":"tap_text","args":{"text":"$words"},"step":$step,"outcome":"$outcome"}"""

    @Test
    fun `nothing that changes the screen is done before there is a plan, while reading the screen is`() =
        runTest {
            says(look, tap("饮品甜点", 0), plan, tap("饮品甜点", 1), done)
            run()
            assertEquals(1, phone.called("tap_text").size)
            assertTrue(asked[2].last().text.startsWith(Planner.PLAN_FIRST))
        }

    @Test
    fun `moving on archives the finished step with what it achieved, and asks to think the plan over`() =
        runTest {
            says(plan, tap("饮品甜点", 1), tap("冰粉", 2, outcome = "进了饮品甜点，10 样"), tap("规格", 3), done)
            run()
            val rethink = asked[3].map { it.text }.joinToString("\n")
            assertTrue(rethink.contains("Harness: Step 1 is archived as done. Before acting on step 2, think again"))
            assertTrue(rethink.contains("[done] 1. 进入饮品甜点 -> 进了饮品甜点，10 样"))
            val noOutcome = asked[4].map { it.text }.joinToString("\n")
            assertTrue(noOutcome.contains("Step 2 is archived as done (say in outcome what a step achieved"))
            val last = events.plans.last()
            assertEquals(listOf(PlanState.DONE, PlanState.DONE, PlanState.DONE), last.map { it.state })
            assertEquals("进了饮品甜点，10 样", last.first().outcome)
        }

    @Test
    fun `a changed plan keeps on record what the old one finished`() =
        runTest {
            val changed = """{"action":"plan","args":{"steps":["换个分类","记下套餐"]}}"""
            val stop = """{"action":"fail","args":{"reason":"测完"}}"""
            says(plan, tap("饮品甜点", 1), tap("冰粉", 2, outcome = "进了饮品甜点"), changed, stop)
            run()
            val record = events.plans.last()
            assertEquals(PlanItem("进入饮品甜点", PlanState.DONE, "进了饮品甜点", earlier = true), record.first())
            assertEquals(listOf("换个分类", "记下套餐"), record.drop(1).map { it.text })
            assertTrue(record.drop(1).all { it.state == PlanState.TODO })
            assertTrue(
                asked
                    .last()
                    .last()
                    .text
                    .contains("Done before the plan was changed:\n- 进入饮品甜点 -> 进了饮品甜点"),
            )
        }

    @Test
    fun `the map goes into the record in the user's words`() =
        runTest {
            says(
                """{"at":"拼好饭 > 饮品甜点","action":"note","args":{"items":{"冰粉":"¥1.9"}}}""",
                """{"action":"fail","args":{"reason":"测完"}}""",
            )
            run()
            val tree = events.trees.last()
            assertEquals(TreeLine(1, "饮品甜点", "在这里 · 列表没看完"), tree[1])
            assertEquals(TreeLine(2, "冰粉", "列表里读到", listOf("¥1.9")), tree[2])
        }

    @Test
    fun `a replayed lookup only reads its screen, so it is not held up for a plan`() =
        runTest {
            phone.skillRun = "status: done\nthen_read: true"
            says("""{"action":"scroll","args":{"direction":"down"}}""", done)
            run()
            assertEquals(1, phone.called("scroll").size)
        }

    @Test
    fun `the board archives, moves on and finishes as the record shows`() {
        val board = TaskBoard()
        board.adopt(listOf("一", "二", "三"))
        assertTrue(board.moveTo(1))
        assertTrue(board.moveTo(2, "一做完了"))
        assertFalse(board.moveTo(2, "不会记"))
        assertEquals(
            listOf(PlanItem("一", PlanState.DONE, "一做完了"), PlanItem("二", PlanState.NOW), PlanItem("三")),
            board.items(),
        )
        board.finish(ok = true)
        assertTrue(board.items().all { it.state == PlanState.DONE })
        val call = AgentProtocol.parse("""{"action":"click_node","args":{"node_id":"n1"},"step":2,"outcome":"进了列表"}""")
        assertEquals(AgentMove.Call("click_node", buildJsonObject { put("node_id", "n1") }, 2, "进了列表"), call)
    }
}
