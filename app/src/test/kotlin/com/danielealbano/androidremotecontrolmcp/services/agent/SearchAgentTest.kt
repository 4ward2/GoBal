package com.danielealbano.androidremotecontrolmcp.services.agent

import com.danielealbano.androidremotecontrolmcp.services.chat.ChatApiResult
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** A lookup as the agent runs it with its search tree: the phone and the model are scripted. */
@DisplayName("PhoneAgent search tree")
class SearchAgentTest {
    private val phone = FakePhone()
    private val events = Recorder()
    private val asked = mutableListOf<List<ChatMessage>>()
    private val replies = ArrayDeque<ChatApiResult>()

    private fun says(vararg json: String) = json.forEach { replies += ChatApiResult(ok = true, content = it) }

    private suspend fun run(goal: String): AgentOutcome {
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
        return PhoneAgent(phone, brain, gate, events, pause = {}).run(goal)
    }

    private val look = """{"action":"get_screen_state","args":{}}"""
    private val done = """{"action":"done","args":{"ok":true,"summary":"已经加到购物车","skill_params":{"店名":"蜜雪冰城"}}}"""

    @Test
    fun `the task keeps a map of places, notes sit at their place, and goto jumps back without walking`() =
        runTest {
            phone.results["task_status"] = ToolReply("goal: 看看拼好饭; steps: 2")
            phone.results["task_goto"] = ToolReply("status: done\n完成")
            says(
                """{"at":"拼好饭 > 饮品甜点","action":"explore","args":{"under":"拼好饭 > 饮品甜点","names":["冰粉","奶茶"]}}""",
                """{"at":"拼好饭 > 饮品甜点","action":"tap_text","args":{"text":"冰粉"}}""",
                """{"at":"拼好饭>饮品甜点>冰粉","action":"note","args":{"text":"小份 ¥1.9，大份 ¥4.9"}}""",
                """{"at":"拼好饭 > 饮品甜点 > 冰粉","action":"goto","args":{"at":"拼好饭 > 饮品甜点"}}""",
                done,
                done,
            )
            run("看看拼好饭")
            val jump = phone.called("task_goto").single()
            assertEquals("2", (jump["step"] as JsonPrimitive).content)
            // One screen (the item) was opened since the list: the back key is pressed once.
            assertEquals("1", (jump["back"] as JsonPrimitive).content)
            val afterJump = asked.map { it.last().text }.first { it.startsWith("You are back at") }
            assertTrue(afterJump.startsWith("You are back at \"拼好饭 > 饮品甜点\". This is the screen:"))
            val map = afterJump.substringAfter(Mapper.MAP_TITLE + "\n")
            assertEquals(
                "- 拼好饭\n  - 饮品甜点 [here, list, end not seen]\n    - 冰粉 [opened]\n" +
                    "        · 小份 ¥1.9，大份 ¥4.9\n    - 奶茶 [to open]",
                map,
            )
            assertTrue(afterJump.contains("[now] 2. See each list to its end"))
            assertTrue(events.steps.any { it.label == "去「拼好饭 > 饮品甜点」" && it.ok })
            assertTrue(events.noted.contains("[拼好饭 > 饮品甜点 > 冰粉] 小份 ¥1.9，大份 ¥4.9"))
        }

    @Test
    fun `items read off a list go into the map at once, and the list is not finished until its end is seen`() =
        runTest {
            val items = """{"冰粉":"¥1.9 广东甜品","奶茶":"¥3.9 兵立王","柠檬水":"¥1.9 蜜雪冰城"}"""
            val finished = """{"at":"拼好饭 > 饮品甜点","action":"done","args":{"ok":true,"summary":"最便宜的是冰粉和柠檬水"}}"""
            says("""{"at":"拼好饭 > 饮品甜点","action":"note","args":{"items":$items}}""", finished, finished)
            val outcome = run("看看拼好饭饮品甜点最便宜的")
            val board = asked[1].last().text
            assertTrue(board.contains("3 item(s) noted under \"拼好饭 > 饮品甜点\"."))
            assertTrue(board.contains("— 拼好饭 > 饮品甜点: 3 items noted, end not seen"))
            assertTrue(board.contains("[now] 2. See each list to its end"))
            assertTrue(board.contains("    - 冰粉 [from the list]\n        · ¥1.9 广东甜品"))
            assertTrue(events.steps.any { it.label == "记下 3 样：冰粉、奶茶、柠檬水" })
            // The first done is sent back; the second is let through, and the user is told what was not seen.
            val sentBack = asked[2].last().text
            assertTrue(sentBack.startsWith("Not finished: these places on your map have no outcome yet:"))
            assertTrue(sentBack.contains("- 拼好饭 > 饮品甜点 (a list not seen to its end)"))
            assertEquals(RunStatus.DONE, outcome.status)
            assertEquals("最便宜的是冰粉和柠檬水\n\n（没看完的：饮品甜点）", outcome.result)
        }

    @Test
    fun `the end of a list is taken from the screen, and then the search ends without being sent back`() =
        runTest {
            phone.screenText = FakePhone.screen("com.sankuai.meituan", "n2" to "更多超值套餐即将上架，敬请期待！")
            says(
                """{"at":"拼好饭 > 饮品甜点","action":"note","args":{"items":{"冰粉":"¥1.9"}}}""",
                """{"at":"拼好饭 > 饮品甜点","action":"scroll","args":{"direction":"down"}}""",
                done,
            )
            val outcome = run("看看拼好饭饮品甜点")
            val afterScroll = asked[2].last().text
            assertTrue(afterScroll.contains("Harness: the end of the list is on the screen (\"敬请期待\")"))
            assertTrue(afterScroll.contains("饮品甜点 [here, list, end seen]"))
            assertTrue(afterScroll.contains("[now] 4. Answer from the map with done"))
            assertTrue(events.steps.any { it.label == "「饮品甜点」到底了" })
            assertEquals(RunStatus.DONE, outcome.status)
            assertEquals("已经加到购物车", outcome.result)
        }

    @Test
    fun `a scroll on that changes nothing means the list is at its end, a scroll back does not`() =
        runTest {
            phone.screenText = FakePhone.screen("com.sankuai.meituan", "n1" to "汉堡 ¥3.9")
            says(
                look,
                """{"at":"拼好饭 > 汉堡","action":"note","args":{"items":{"汉堡":"¥3.9"}}}""",
                """{"at":"拼好饭 > 汉堡","action":"scroll","args":{"direction":"up"}}""",
                """{"at":"拼好饭 > 汉堡","action":"scroll","args":{"direction":"down"}}""",
                done,
            )
            run("看看汉堡")
            assertFalse(asked[3].last().text.contains("Harness: scrolling changed nothing"))
            assertTrue(asked[4].last().text.contains("Harness: scrolling changed nothing, so \"拼好饭 > 汉堡\""))
        }

    @Test
    fun `opening an item before its list is seen whole is pointed out once`() =
        runTest {
            phone.results["task_status"] = ToolReply("steps: 1")
            says(
                """{"at":"拼好饭 > 饮品甜点","action":"note","args":{"items":{"冰粉":"¥1.9","奶茶":"¥3.9"}}}""",
                """{"at":"拼好饭 > 饮品甜点 > 冰粉","action":"get_screen_state","args":{}}""",
                """{"at":"拼好饭 > 饮品甜点 > 奶茶","action":"get_screen_state","args":{}}""",
                """{"action":"fail","args":{"reason":"测完"}}""",
            )
            run("看看拼好饭")
            val warning = "Harness: \"拼好饭 > 饮品甜点\" is not seen to its end. If you are choosing from it"
            val said = asked.map { messages -> messages.joinToString("\n") { it.text } }
            assertFalse(said[1].contains(warning))
            assertTrue(said[2].contains(warning))
            assertEquals(1, Regex(Regex.escape(warning)).findAll(said.last()).count())
        }

    @Test
    fun `a place closed with a reason needs nothing more, and a place not on the map cannot be closed`() =
        runTest {
            says(
                """{"at":"拼好饭 > 饮品甜点","action":"explore","args":{"names":["冰粉","奶茶"]}}""",
                """{"at":"拼好饭 > 饮品甜点","action":"close","args":{"at":"火锅","reason":"没有"}}""",
                """{"at":"拼好饭 > 饮品甜点","action":"close","args":{"at":"奶茶","reason":"比冰粉贵"}}""",
                """{"at":"拼好饭 > 饮品甜点","action":"close","args":{"reason":"按价格排过序，后面更贵"}}""",
                """{"at":"拼好饭 > 饮品甜点","action":"note","args":{"about":"冰粉","text":"¥1.9 一种规格"}}""",
                done,
            )
            val outcome = run("看看拼好饭")
            assertTrue(asked[2].last().text.startsWith("There is no place \"火锅\" on the map."))
            assertTrue(asked[3].last().text.contains("    - 奶茶 [closed: 比冰粉贵]"))
            assertTrue(asked[5].last().text.contains("[now] 4. Answer from the map with done"))
            assertTrue(events.steps.any { it.label == "不看「奶茶」：比冰粉贵" })
            assertTrue(events.steps.any { it.label == "看完「饮品甜点」：按价格排过序，后面更贵" })
            assertEquals(RunStatus.DONE, outcome.status)
        }

    @Test
    fun `a place the screen does not show is not taken on the model's word, nor is what it notes there`() =
        runTest {
            phone.screenText = FakePhone.screen("com.sankuai.meituan", "n1" to "冰粉 ¥1.9")
            says(
                """{"at":"拼好饭 > 饮品甜点","action":"get_screen_state","args":{}}""",
                """{"at":"拼好饭 > 饮品甜点","action":"note","args":{"items":{"冰粉":"¥1.9"}}}""",
                """{"at":"拼好饭 > 饮品甜点 > 冰粉","action":"note","args":{"text":"大杯 ¥3.9，超大杯 ¥5.9"}}""",
                """{"action":"fail","args":{"reason":"测完"}}""",
            )
            run("看看拼好饭")
            val told = asked[3].last().text
            assertTrue(told.startsWith("Nothing was noted. The screen has not changed since you were at "))
            assertTrue(told.contains("so the map keeps you there"))
            assertFalse(told.contains("大杯 ¥3.9"))
            assertTrue(told.contains("饮品甜点 [here, list, end not seen]"))
            assertTrue(events.steps.any { it.label == "还在「饮品甜点」" && !it.ok })
            assertFalse(events.steps.any { it.label.startsWith("到了「拼好饭 > 饮品甜点 > 冰粉") })
        }

    @Test
    fun `when the jump cannot be made, the back key is pressed once for each screen opened since the place`() =
        runTest {
            phone.results["task_status"] = ToolReply("steps: 2")
            phone.results["task_goto"] = ToolReply("Nothing is recorded up to step 2", isError = true)
            says(
                """{"at":"拼好饭 > 饮品","action":"get_screen_state","args":{}}""",
                """{"at":"拼好饭 > 饮品 > 冰粉","action":"get_screen_state","args":{}}""",
                """{"at":"拼好饭 > 饮品 > 冰粉 > 规格","action":"goto","args":{"at":"拼好饭 > 饮品"}}""",
                """{"at":"拼好饭 > 饮品","action":"goto","args":{"at":"拼好饭 > 汉堡"}}""",
                """{"action":"fail","args":{"reason":"测完"}}""",
            )
            phone.screenAfterFirstRead = FakePhone.screen("com.sankuai.meituan", "n9" to "冰粉详情")
            run("看看拼好饭")
            assertEquals(2, phone.called("press_back").size)
            val back = asked[3].last().text
            assertTrue(back.startsWith("The jump could not be made, so the phone went back 2 screen(s)"))
            assertTrue(events.steps.any { it.label == "去「拼好饭 > 饮品」" && it.ok })
            assertTrue(asked[4].last().text.startsWith("You have not been to \"拼好饭 > 汉堡\""))
            assertEquals(2, phone.called("press_back").size)
        }
}
