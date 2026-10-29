package com.danielealbano.androidremotecontrolmcp.services.agent

import com.danielealbano.androidremotecontrolmcp.services.chat.ChatApiResult
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The phone as the agent sees it in these tests: scripted tool results and a record of every call. */
internal class FakePhone : AgentTools {
    var ready: String? = null
    var skillRun = "status: no_match"
    var screenText = screen("com.android.launcher", "n1" to "美团")
    var taskEnd = "No skill saved."
    var skillOpens = emptyList<String>()

    /** What the screen turns into once the model has read it (a page that finished loading). */
    var screenAfterFirstRead: String? = null

    /** Further pages of the screen, by cursor. */
    val pages = mutableMapOf<String, String>()
    val results = mutableMapOf<String, ToolReply>()
    val calls = mutableListOf<Pair<String, JsonObject>>()

    override fun notReady(): String? = ready

    override fun specs(): List<ToolSpec> = PhoneAgent.OFFERED.map { ToolSpec(it, "does $it") }

    override suspend fun call(
        tool: String,
        args: JsonObject,
    ): ToolReply {
        calls += tool to args
        return when (tool) {
            "skill_run" -> ToolReply(skillRun)
            "get_screen_state" -> readScreen(args)
            "task_end" -> ToolReply(taskEnd)
            else -> results[tool] ?: ToolReply("ok")
        }
    }

    override fun appName(packageName: String): String = if (packageName == "com.sankuai.meituan") "美团" else packageName

    override fun skills(): List<String> = listOf("点{{店名}}外卖")

    override fun skillApps(nameOrRequest: String): List<String> = skillOpens

    private fun readScreen(args: JsonObject): ToolReply {
        val cursor = (args["cursor"] as? JsonPrimitive)?.content
        val shown = if (cursor == null) screenText else pages[cursor]
        if (cursor == null) screenAfterFirstRead?.let { screenText = it }
        return ToolReply(shown ?: "note:invalid cursor")
    }

    fun called(tool: String): List<JsonObject> = calls.filter { it.first == tool }.map { it.second }

    companion object {
        private const val CLOCK = "s1\tTextView\t12:00\t-\t-\t0,0,10,10\ton"

        /** The app's window comes second, after the status bar's, as on a real phone. */
        fun screen(
            app: String,
            vararg nodes: Pair<String, String>,
            more: String = "",
        ): String =
            buildString {
                appendLine("note:flags")
                appendLine("screen:1080x2400 density:420 orientation:portrait")
                append(window("SYSTEM", "com.android.systemui", focused = false, listOf(CLOCK), "s1"))
                val rows = nodes.map { (id, text) -> "$id\tTextView\t$text\t-\t-\t0,0,10,10\ton,clk" }
                append(window("APPLICATION", app, focused = true, rows, nodes.joinToString("\n") { it.first }))
                append(more)
            }.trimEnd()

        fun window(
            type: String,
            app: String,
            focused: Boolean,
            rows: List<String>,
            tree: String,
        ): String =
            buildString {
                appendLine("--- window:1 type:$type pkg:$app title:Main page layer:1 focused:$focused ---")
                appendLine("node_id\tclass\ttext\tdesc\tres_id\tbounds\tflags")
                rows.forEach(::appendLine)
                appendLine("hierarchy:")
                appendLine(tree)
            }
    }
}

internal class Recorder : AgentEvents {
    val steps = mutableListOf<AgentStep>()
    val waiting = mutableListOf<Boolean>()
    var archivedAs = ""
    val notes = ArrayDeque<String>()

    override fun step(step: AgentStep) {
        steps += step
    }

    override fun waitingForApproval(waiting: Boolean) {
        this.waiting += waiting
    }

    override fun archived(skillName: String) {
        archivedAs = skillName
    }

    val noted = mutableListOf<String>()

    override fun noted(text: String) {
        noted += text
    }

    val plans = mutableListOf<List<PlanItem>>()
    val trees = mutableListOf<List<TreeLine>>()

    override fun planned(plan: List<PlanItem>) {
        plans += plan
    }

    override fun mapped(tree: List<TreeLine>) {
        trees += tree
    }

    override fun takeNotes(): List<String> = notes.toList().also { notes.clear() }
}

@DisplayName("PhoneAgent")
class PhoneAgentTest {
    private val phone = FakePhone()
    private val events = Recorder()
    private val asked = mutableListOf<List<ChatMessage>>()
    private val replies = ArrayDeque<ChatApiResult>()
    private var allowApps = true
    private val gated = mutableListOf<String>()
    private val denied = mutableSetOf<String>()

    private fun says(vararg json: String) = json.forEach { replies += ChatApiResult(ok = true, content = it) }

    private suspend fun run(goal: String = "帮我点一杯蜜雪冰城"): AgentOutcome {
        val brain =
            AgentBrain { messages ->
                asked += messages
                replies.removeFirstOrNull() ?: ChatApiResult(ok = true, content = look)
            }
        val gate =
            object : AppGate {
                private val answered = mutableMapOf("com.android.launcher" to true)

                override fun settled(packageName: String): Boolean? = answered[packageName]

                override suspend fun ask(packageName: String): Boolean {
                    gated += packageName
                    return (allowApps && packageName !in denied).also { answered[packageName] = it }
                }
            }
        return PhoneAgent(phone, brain, gate, events, pause = {}).run(goal)
    }

    /** What a model that never gets anywhere keeps answering. */
    private val look = """{"action":"get_screen_state","args":{}}"""
    private val open = """{"thought":"先打开美团","action":"open_app","args":{"package_id":"com.sankuai.meituan"}}"""
    private val done = """{"action":"done","args":{"ok":true,"summary":"已经加到购物车","skill_params":{"店名":"蜜雪冰城"}}}"""

    @Test
    fun `without the accessibility service nothing is tried and the user is told what to switch on`() =
        runTest {
            phone.ready = InAppTools.NEEDS_ACCESSIBILITY
            assertEquals(AgentOutcome(RunStatus.FAILED, InAppTools.NEEDS_ACCESSIBILITY), run())
            assertTrue(phone.calls.isEmpty())
            assertTrue(asked.isEmpty())
        }

    @Test
    fun `a saved skill that fits is replayed without asking the model`() =
        runTest {
            phone.skillRun = "status: done\nsteps: 7"
            val outcome = run()
            assertEquals(RunStatus.DONE, outcome.status)
            assertTrue(asked.isEmpty())
            assertEquals(listOf(AgentStep("重放技能")), events.steps)
            assertEquals("帮我点一杯蜜雪冰城", (phone.called("skill_run").single()["utterance"] as JsonPrimitive).content)
            assertTrue(phone.called("task_begin").isEmpty())
        }

    @Test
    fun `a skill that stops for the user hands the task over`() =
        runTest {
            phone.skillRun = "status: handed_over\n到了提交订单"
            assertEquals(AgentOutcome(RunStatus.HANDED_OVER, "到了提交订单"), run())
        }

    @Test
    fun `a skill that breaks part-way is carried on by the model from the current screen`() =
        runTest {
            phone.skillRun = "status: failed\nstopped at step 3: 找不到「搜索」"
            says(done)
            assertEquals(RunStatus.DONE, run().status)
            val request = asked.single()[1].text
            assertTrue(request.contains("找不到「搜索」"))
            assertTrue(request.contains("Carry on from the current screen"))
            assertFalse(events.steps.first().ok)
        }

    @Test
    fun `the model opens an app, taps, and finishing archives the task`() =
        runTest {
            phone.taskEnd = "archived: 点{{店名}}外卖\nSaved."
            says(open, """{"action":"click_node","args":{"node_id":"n1"}}""", done)
            val outcome = run()
            assertEquals(AgentOutcome(RunStatus.DONE, "已经加到购物车"), outcome)
            assertEquals(listOf("打开「美团」", "点「美团」"), events.steps.map { it.label })
            assertEquals("点{{店名}}外卖", events.archivedAs)
            assertEquals(listOf("com.sankuai.meituan"), gated)
            assertEquals(listOf(true, false), events.waiting)
            val end = phone.called("task_end").single()
            assertEquals("true", (end["success"] as JsonPrimitive).content)
            assertEquals("蜜雪冰城", (end["params"]!!.jsonObject["店名"] as JsonPrimitive).content)
            assertEquals("帮我点一杯蜜雪冰城", (phone.called("task_begin").single()["goal"] as JsonPrimitive).content)
        }

    @Test
    fun `the screen is read again after anything that changes it, so the model need not ask`() =
        runTest {
            says(open, done)
            run()
            assertEquals(1, phone.called("get_screen_state").size)
            val afterOpen = asked[1].last().text
            assertTrue(afterOpen.startsWith("Result of open_app: ok"))
            assertTrue(afterOpen.contains("n1\tTextView\t美团"))
        }

    @Test
    fun `only the newest screen is sent in full`() =
        runTest {
            says(open, """{"action":"click_node","args":{"node_id":"n1"}}""", done)
            run()
            val last = asked.last()
            val observations = last.filter { it.role == "user" }.drop(1)
            assertTrue(observations.first().text.contains("(an earlier screen, omitted)"))
            assertFalse(observations.first().text.contains("TextView"))
            assertTrue(observations.last().text.contains("TextView"))
        }

    @Test
    fun `it stops before a payment and never taps the button`() =
        runTest {
            phone.screenText = FakePhone.screen("com.sankuai.meituan", "p1" to "提交订单 ¥15")
            says("""{"action":"get_screen_state","args":{}}""", """{"action":"click_node","args":{"node_id":"p1"}}""")
            val outcome = run()
            assertEquals(RunStatus.HANDED_OVER, outcome.status)
            assertTrue(outcome.result.contains("付款"))
            assertTrue(phone.called("click_node").isEmpty())
            assertEquals("false", (phone.called("task_end").single()["success"] as JsonPrimitive).content)
        }

    @Test
    fun `a pay button whose words sit on a child control is still not tapped`() =
        runTest {
            phone.screenText =
                FakePhone.window(
                    "APPLICATION",
                    "com.sankuai.meituan",
                    focused = true,
                    listOf(
                        "b1\tViewGroup\t-\t-\tsubmit\t0,0,10,10\ton,clk",
                        "t1\tTextView\t去支付\t-\t-\t0,0,10,10\ton",
                        "o1\tTextView\t备注\t-\t-\t0,0,10,10\ton,clk",
                    ),
                    "b1\n  t1\no1",
                )
            says("""{"action":"get_screen_state","args":{}}""", """{"action":"click_node","args":{"node_id":"b1"}}""")
            val outcome = run()
            assertEquals(RunStatus.HANDED_OVER, outcome.status)
            assertTrue(outcome.result.contains("去支付"))
            assertTrue(phone.called("click_node").isEmpty())
        }

    @Test
    fun `taps by coordinate and arbitrary links are not on offer`() {
        assertFalse("swipe" in PhoneAgent.OFFERED)
        assertFalse("tap" in PhoneAgent.OFFERED)
        assertFalse("long_press" in PhoneAgent.OFFERED)
        assertFalse("open_uri" in PhoneAgent.OFFERED)
        assertFalse("press_key" in PhoneAgent.OFFERED)
    }

    @Test
    fun `an app the user does not allow is not opened and the task stops`() =
        runTest {
            allowApps = false
            says(open)
            val outcome = run()
            assertEquals(RunStatus.STOPPED, outcome.status)
            assertTrue(outcome.result.contains("美团"))
            assertTrue(phone.called("open_app").isEmpty())
        }

    @Test
    fun `a captcha that stays through the cooldowns is handed to the user, and nothing on it is tapped`() =
        runTest {
            phone.screenText = FakePhone.screen("com.sankuai.meituan", "c1" to "请向右滑动滑块验证")
            val scroll = """{"action":"scroll","args":{}}"""
            says(open, scroll, scroll, """{"action":"click_node","args":{"node_id":"c1"}}""")
            val outcome = run()
            assertEquals(RunStatus.HANDED_OVER, outcome.status)
            assertTrue(phone.called("click_node").isEmpty())
            assertEquals(2, phone.called("close_app").size)
        }

    @Test
    fun `the model can hand over or give up itself`() =
        runTest {
            says("""{"action":"hand_over","args":{"reason":"需要登录"}}""")
            assertEquals(AgentOutcome(RunStatus.HANDED_OVER, "需要登录"), run())
            says("""{"action":"fail","args":{"reason":"没装这个应用"}}""")
            assertEquals(AgentOutcome(RunStatus.FAILED, "没装这个应用"), run())
        }

    @Test
    fun `an unreadable reply is asked again, and three in a row end the task`() =
        runTest {
            says("我想想……", done)
            assertEquals(RunStatus.DONE, run().status)
            assertTrue(
                asked
                    .last()
                    .last()
                    .text
                    .contains("exactly one JSON object"),
            )
            says("一", "二", "三")
            val outcome = run()
            assertEquals(RunStatus.FAILED, outcome.status)
            assertTrue(outcome.result.contains("看不懂"))
        }

    @Test
    fun `a tool that is not on offer is refused and the model told`() =
        runTest {
            says("""{"action":"delete_file","args":{"path":"/sdcard/a"}}""", done)
            assertEquals(RunStatus.DONE, run().status)
            assertTrue(phone.called("delete_file").isEmpty())
            assertTrue(
                asked
                    .last()
                    .last()
                    .text
                    .contains("no tool named delete_file"),
            )
        }

    @Test
    fun `a model that does not answer ends the task with the reason`() =
        runTest {
            replies += ChatApiResult(ok = false, error = "HTTP 500: boom")
            val outcome = run()
            assertEquals(RunStatus.FAILED, outcome.status)
            assertTrue(outcome.result.contains("HTTP 500"))
        }

    @Test
    fun `a task that never finishes is stopped after the step limit`() =
        runTest {
            val outcome = run()
            assertEquals(AgentOutcome(RunStatus.FAILED, PhoneAgent.OUT_OF_STEPS), outcome)
            assertEquals(PhoneAgent.MAX_STEPS, asked.size)
        }

    @Test
    fun `a plan is kept in front of the model with its progress, and gives a long task more room`() =
        runTest {
            val plan = """{"action":"plan","args":{"steps":["打开美团，看到首页","进入拼好饭，看到商品列表","记下商品"]}}"""
            says(plan, """{"step":1,"action":"open_app","args":{"package_id":"com.sankuai.meituan"}}""")
            val outcome = run("看看今天的拼好饭")
            assertEquals(RunStatus.FAILED, outcome.status)
            assertEquals(PhoneAgent.MAX_STEPS + 3 * 12, asked.size)
            assertTrue(asked[1].last().text.contains("Plan saved"))
            val board = asked[2].last().text
            assertTrue(board.contains("[now] 1. 打开美团，看到首页"))
            assertTrue(board.contains("[todo] 2. 进入拼好饭，看到商品列表"))
            assertEquals("定计划：1.打开美团，看到首页；2.进入拼好饭，看到商品列表；3.记下商品", events.steps.first().label)
            assertTrue(events.steps.any { it.label == "第1步：打开美团，看到首页" })
        }

    @Test
    fun `what the model writes down outlives the screen it was on`() =
        runTest {
            says(
                open,
                """{"action":"note","args":{"text":"蜜雪冰城 柠檬水 ¥3.9"}}""",
                """{"action":"press_back","args":{}}""",
                done,
            )
            run()
            assertTrue(asked[2].last().text.endsWith("Noted.\n\nNotes you wrote:\n- 蜜雪冰城 柠檬水 ¥3.9"))
            assertTrue(
                asked
                    .last()
                    .last()
                    .text
                    .contains("- 蜜雪冰城 柠檬水 ¥3.9"),
            )
            assertTrue(events.steps.any { it.label == "记下：蜜雪冰城 柠檬水 ¥3.9" })
        }

    @Test
    fun `the harness speaks up when the model acts without a plan or goes in circles`() =
        runTest {
            run()
            val said = asked.flatMap { messages -> messages.map { it.text } }
            assertTrue(said.any { it.contains("Harness: " + Planner.REPEATING) })
            assertTrue(said.any { it.contains("Harness: " + Planner.NO_PLAN) })
        }

    @Test
    fun `turns long past are folded into a line each`() =
        runTest {
            says(open)
            run()
            val last = asked.last()
            val opening = last[1].text
            val folded = "Earlier steps (their screens are gone):\n1. open_app -> Result of open_app: ok"
            assertTrue(opening.contains(folded))
            assertEquals(2 + 2 * 8, last.size)
        }

    @Test
    fun `tapping by words is on offer, and words that pay are never tapped that way either`() =
        runTest {
            says(
                open,
                """{"action":"tap_text","args":{"text":"饮品甜点"}}""",
                """{"action":"tap_text","args":{"text":"去支付"}}""",
            )
            val outcome = run()
            assertEquals(RunStatus.HANDED_OVER, outcome.status)
            assertTrue(outcome.result.contains("去支付"))
            assertEquals(listOf("饮品甜点"), phone.called("tap_text").map { (it["text"] as JsonPrimitive).content })
            assertTrue(events.steps.any { it.label == "点「饮品甜点」" })
        }

    @Test
    fun `a lookup is saved to end in a reading, with the detours the model names left out`() =
        runTest {
            says(
                open,
                """{"action":"note","args":{"text":"柠檬水 ¥3.9"}}""",
                """{"action":"done","args":{"ok":true,"summary":"柠檬水 ¥3.9","skill_drop":[2,3]}}""",
            )
            run("看看今天的拼好饭")
            val end = phone.called("task_end").single()
            assertEquals("true", (end["then_read"] as JsonPrimitive).content)
            assertEquals("[2,3]", end["drop"].toString())
            assertEquals(listOf("柠檬水 ¥3.9"), events.noted)
        }

    @Test
    fun `a task that went well but could not be saved as a skill shows why`() =
        runTest {
            phone.taskEnd = "没有存成技能：这次用了重放做不到的操作：左右滑动"
            says(open, done)
            assertEquals(RunStatus.DONE, run().status)
            assertEquals(AgentStep("没有存成技能：这次用了重放做不到的操作：左右滑动", ok = false), events.steps.last())
            assertEquals("", events.archivedAs)
        }

    @Test
    fun `the saved skill ends where the reading began, and the prompt says a list price is a starting price`() =
        runTest {
            phone.results["task_status"] = ToolReply("goal: 看看拼好饭; steps: 2\n1. 打开美团\n2. 点「拼好饭」")
            says(
                open,
                """{"action":"note","args":{"text":"冰粉 ¥1.9 起"}}""",
                """{"action":"tap_text","args":{"text":"冰粉"}}""",
                """{"action":"note","args":{"text":"冰粉：小份 ¥1.9，大份 ¥4.9"}}""",
                done,
            )
            run("看看拼好饭")
            assertEquals("2", (phone.called("task_end").single()["keep"] as JsonPrimitive).content)
            assertEquals(1, phone.called("task_status").size)
            assertTrue(
                asked
                    .first()
                    .first()
                    .text
                    .contains("only the lowest of several options"),
            )
        }

    @Test
    fun `what is opened after a replayed lookup does not change the saved skill`() =
        runTest {
            phone.skillRun = "status: done\nskill: 看看拼好饭\nsteps: 2/2\nmessage: 完成\nthen_read: true"
            says("""{"action":"tap_text","args":{"text":"冰粉"}}""", done)
            run("看看拼好饭")
            assertEquals("false", (phone.called("task_end").single()["success"] as JsonPrimitive).content)
        }

    @Test
    fun `a jump to a place the task has not been to is refused with the places it can jump to`() =
        runTest {
            says("""{"at":"拼好饭","action":"goto","args":{"at":"拼好饭 > 汉堡披萨"}}""", done)
            run("看看拼好饭")
            assertTrue(phone.called("task_goto").isEmpty())
            assertTrue(
                asked
                    .last()
                    .last()
                    .text
                    .contains("Places you can jump to: 拼好饭."),
            )
        }

    @Test
    fun `an app is opened at its first screen, which is where a replay of the task will start it`() =
        runTest {
            says(open, done)
            run()
            assertEquals("true", (phone.called("open_app").single()["fresh"] as JsonPrimitive).content)
        }

    @Test
    fun `a replayed lookup is read by the model and is not recorded over`() =
        runTest {
            phone.skillRun = "status: done\nskill: 看看今天的拼好饭\nsteps: 2/2\nmessage: 完成\nthen_read: true"
            says(look, """{"action":"done","args":{"ok":true,"summary":"今天有柠檬水"}}""")
            val outcome = run("看看今天的拼好饭")
            assertEquals(AgentOutcome(RunStatus.DONE, "今天有柠檬水"), outcome)
            assertTrue(asked.first()[1].text.contains("A saved skill for this request has just been replayed"))
            assertEquals("false", (phone.called("task_end").single()["success"] as JsonPrimitive).content)
            assertEquals("", events.archivedAs)
        }

    @Test
    fun `tapping the same control again and again brings a hint to tap by words`() =
        runTest {
            val tap = """{"action":"click_node","args":{"node_id":"n1"}}"""
            says(tap, look, tap, look, tap)
            run()
            val said = asked.flatMap { messages -> messages.map { it.text } }
            assertTrue(said.any { it.contains("Harness: " + Planner.CIRCLING) })
        }

    @Test
    fun `what the user adds while it runs reaches the model at the next step`() =
        runTest {
            says(open, done)
            events.notes += "要少冰"
            run()
            assertTrue(asked.first()[1].text.contains("The user adds: 要少冰"))
            assertTrue(events.notes.isEmpty())
        }

    @Test
    fun `a failed tool call is shown as a failed step and the model sees why`() =
        runTest {
            phone.results["click_node"] = ToolReply("ACTION_CLICK failed on node n1", isError = true)
            says("""{"action":"click_node","args":{"node_id":"n1"}}""", done)
            run()
            assertFalse(events.steps.single().ok)
            assertTrue(
                asked
                    .last()
                    .last()
                    .text
                    .contains("ACTION_CLICK failed"),
            )
        }

    @Test
    fun `a control that is not on the screen is not acted on, and the model is shown the screen`() =
        runTest {
            says("""{"action":"click_node","args":{"node_id":"n9"}}""", done)
            assertEquals(RunStatus.DONE, run().status)
            assertTrue(phone.called("click_node").isEmpty())
            val told =
                asked
                    .last()
                    .last()
                    .text
            assertTrue(told.contains("no control n9"))
            assertTrue(told.contains("n1\tTextView\t美团"))
        }

    @Test
    fun `a pay button in a window of its own, such as a bottom sheet, is seen`() =
        runTest {
            val button = "p1\tButton\t确认支付\t-\t-\t0,0,9,9\ton,clk"
            val sheet = FakePhone.window("APPLICATION", "com.sankuai.meituan", false, listOf(button), "p1")
            phone.screenText = FakePhone.screen("com.sankuai.meituan", "o1" to "订单详情", more = sheet)
            says("""{"action":"click_node","args":{"node_id":"p1"}}""")
            assertEquals(RunStatus.HANDED_OVER, run().status)
            assertTrue(phone.called("click_node").isEmpty())
        }

    @Test
    fun `typing into a pay button, which would click it first, is refused like a tap`() =
        runTest {
            phone.screenText = FakePhone.screen("com.sankuai.meituan", "p1" to "提交订单")
            says("""{"action":"type_append_text","args":{"node_id":"p1","text":"x"}}""")
            assertEquals(RunStatus.HANDED_OVER, run().status)
            assertTrue(phone.called("type_append_text").isEmpty())
            says("""{"action":"type_clear_text","args":{"node_id":"p1"}}""")
            assertEquals(RunStatus.HANDED_OVER, run().status)
            assertTrue(phone.called("type_clear_text").isEmpty())
        }

    @Test
    fun `a button that became a pay button after the model looked is not tapped`() =
        runTest {
            phone.screenText = FakePhone.screen("com.sankuai.meituan", "p1" to "加载中")
            phone.screenAfterFirstRead = FakePhone.screen("com.sankuai.meituan", "p1" to "去支付 ¥15")
            says(look, """{"action":"click_node","args":{"node_id":"p1"}}""")
            assertEquals(RunStatus.HANDED_OVER, run().status)
            assertTrue(phone.called("click_node").isEmpty())
        }

    @Test
    fun `a pay button on a later page of a long screen is found there`() =
        runTest {
            val more = "note:more nodes available — call get_screen_state with cursor \"s9.2\" to continue."
            phone.screenText = FakePhone.screen("com.sankuai.meituan", "o1" to "订单详情") + "\n" + more
            phone.pages["s9.2"] = FakePhone.screen("com.sankuai.meituan", "p2" to "立即支付")
            says("""{"action":"click_node","args":{"node_id":"p2"}}""")
            assertEquals(RunStatus.HANDED_OVER, run().status)
            assertTrue(phone.called("click_node").isEmpty())
        }

    @Test
    fun `an app the task wanders into without being allowed stops it there`() =
        runTest {
            denied += "com.eg.pay"
            phone.screenAfterFirstRead = FakePhone.screen("com.eg.pay", "x1" to "收银台")
            says(look, """{"action":"scroll","args":{}}""", """{"action":"scroll","args":{}}""")
            val outcome = run()
            assertEquals(RunStatus.STOPPED, outcome.status)
            assertTrue(outcome.result.contains("com.eg.pay"))
            assertEquals(1, phone.called("scroll").size)
        }

    @Test
    fun `a saved skill is not replayed into an app the user does not allow`() =
        runTest {
            allowApps = false
            phone.skillOpens = listOf("com.sankuai.meituan")
            phone.skillRun = "status: done"
            assertEquals(RunStatus.STOPPED, run().status)
            assertTrue(phone.called("skill_run").isEmpty())
            assertTrue(phone.called("task_end").isEmpty())
        }

    @Test
    fun `what a service says in its error is not kept, only what went wrong`() =
        runTest {
            replies += ChatApiResult(ok = false, error = "HTTP 401: Incorrect API key provided: sk-ab****wxyz")
            val outcome = run()
            assertEquals(RunStatus.FAILED, outcome.status)
            assertFalse(outcome.result.contains("sk-"))
            assertTrue(outcome.result.contains("密钥"))
        }

    @Test
    fun `a reply that cannot be used now and then does not end the task, only three in a row do`() =
        runTest {
            says("一", look, "二", look, "三", look, done)
            assertEquals(RunStatus.DONE, run().status)
        }
}
