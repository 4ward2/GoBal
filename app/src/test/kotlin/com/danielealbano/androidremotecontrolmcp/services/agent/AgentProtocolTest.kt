package com.danielealbano.androidremotecontrolmcp.services.agent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("Agent protocol, screen text and step names")
class AgentProtocolTest {
    @Test
    fun `a tool call is read with its arguments`() {
        val move = AgentProtocol.parse("""{"thought":"点搜索","action":"click_node","args":{"node_id":"n7"}}""")
        assertEquals(AgentMove.Call("click_node", buildJsonObject { put("node_id", "n7") }), move)
    }

    @Test
    fun `a plan, a note and the step a call belongs to are read`() {
        val plan = AgentProtocol.parse("""{"action":"plan","args":{"steps":["打开美团","进入拼好饭"]}}""")
        assertEquals(AgentMove.Plan(listOf("打开美团", "进入拼好饭")), plan)
        assertEquals(AgentMove.Plan(emptyList()), AgentProtocol.parse("""{"action":"plan","args":{}}"""))
        val note = AgentProtocol.parse("""{"action":"note","args":{"text":"柠檬水 ¥3.9"}}""")
        assertEquals(AgentMove.Note("柠檬水 ¥3.9"), note)
        val call = AgentProtocol.parse("""{"step":2,"action":"press_back","args":{}}""") as AgentMove.Call
        assertEquals(2, call.step)
        val prompt = AgentProtocol.systemPrompt(emptyList(), emptyList())
        assertTrue(prompt.contains("- plan {steps: [string]}"))
        assertTrue(prompt.contains("- note {text?: string, about?: string, items?: {name: text}}"))
        assertTrue(prompt.contains("- close {at: string, reason: string}"))
    }

    @Test
    fun `the board shows what is done, what is in hand and what is left, and stops taking notes when full`() {
        val board = TaskBoard()
        assertEquals("", board.render())
        board.adopt(listOf(" 打开美团 ", "", "进入拼好饭", "记下商品"))
        assertTrue(board.moveTo(2))
        assertFalse(board.moveTo(2))
        assertFalse(board.moveTo(9))
        assertTrue(board.write("柠檬水 ¥3.9"))
        assertEquals(
            "Plan:\n[done] 1. 打开美团\n[now] 2. 进入拼好饭\n[todo] 3. 记下商品\nNotes you wrote:\n- 柠檬水 ¥3.9",
            board.render(),
        )
        repeat(7) { assertTrue(board.write("x".repeat(1_100))) }
        assertFalse(board.write("x".repeat(1_100)))
        assertFalse(board.write("  "))
    }

    @Test
    fun `text and code fences around the object are ignored`() {
        val reply = "好的，我来操作。\n```json\n{\"action\":\"press_back\",\"args\":{}}\n```\n完成后告诉你。"
        assertEquals(AgentMove.Call("press_back", buildJsonObject { }), AgentProtocol.parse(reply))
    }

    @Test
    fun `braces inside strings do not confuse the reader`() {
        val reply = """{"action":"type_append_text","args":{"node_id":"n1","text":"a } b { c \" d"}}"""
        val move = AgentProtocol.parse(reply)
        assertEquals("a } b { c \" d", ((move as AgentMove.Call).args["text"] as JsonPrimitive).content)
    }

    @Test
    fun `done carries the summary and the words that change next time`() {
        val move = AgentProtocol.parse("""{"action":"done","args":{"summary":"好了","skill_params":{"店名":"瑞幸","n":3}}}""")
        assertEquals(AgentMove.Done(ok = true, summary = "好了", params = mapOf("店名" to "瑞幸", "n" to "3")), move)
        val gaveUp = AgentProtocol.parse("""{"action":"done","args":{"ok":false}}""")
        assertEquals(AgentMove.Done(ok = false, summary = ""), gaveUp)
    }

    @Test
    fun `fail and hand over are moves of their own`() {
        assertEquals(AgentMove.Done(false, "没装"), AgentProtocol.parse("""{"action":"fail","args":{"reason":"没装"}}"""))
        val handed = AgentProtocol.parse("""{"action":"hand_over","args":{"reason":"要登录"}}""")
        assertEquals(AgentMove.HandOver("要登录"), handed)
    }

    @Test
    fun `replies that are not a move say what is wrong`() {
        assertTrue(AgentProtocol.parse("我不知道怎么做") is AgentMove.Invalid)
        assertTrue(AgentProtocol.parse("""{"thought":"嗯"}""") is AgentMove.Invalid)
        assertTrue(AgentProtocol.parse("{ not json") is AgentMove.Invalid)
    }

    @Test
    fun `missing args count as none`() {
        val home = AgentProtocol.parse("""{"action":"press_home"}""")
        assertEquals(AgentMove.Call("press_home", buildJsonObject { }), home)
    }

    @Test
    fun `the prompt lists the tools, the three ways to end and the saved skills`() {
        val tools = listOf(ToolSpec("click_node", "Click the node.\nMore detail.", mapOf("node_id*" to "string")))
        val prompt = AgentProtocol.systemPrompt(tools, listOf("点{{店名}}外卖"))
        assertTrue(prompt.contains("- click_node {node_id*: string} — Click the node."))
        assertFalse(prompt.contains("More detail."))
        assertTrue(prompt.contains("- done {"))
        assertTrue(prompt.contains("- hand_over {"))
        assertTrue(prompt.contains("NEVER pay"))
        assertTrue(prompt.contains("点{{店名}}外卖"))
        assertFalse(AgentProtocol.systemPrompt(tools, emptyList()).contains("Saved skills"))
    }

    @Test
    fun `the screen table gives the app in front and what each control says`() {
        val text =
            listOf(
                "note:flags: on=onscreen",
                "screen:1080x2400 density:420 orientation:portrait",
                "--- window:5 type:APPLICATION pkg:com.sankuai.meituan title:美团 layer:1 focused:true ---",
                "node_id\tclass\ttext\tdesc\tres_id\tbounds\tflags",
                "n1\tTextView\t搜索\t-\tsearch\t0,0,1,1\ton,clk",
                "n2\tImageView\t-\t购物车\t-\t0,0,1,1\ton,clk",
                "n3\tView\t-\t-\tbox\t0,0,1,1\ton",
                "hierarchy:",
                "n1",
                "  n2",
            ).joinToString("\n")
        val screen = ScreenText.parse(text)
        assertEquals("com.sankuai.meituan", screen.packageName)
        assertEquals(mapOf("n1" to "搜索", "n2" to "购物车"), screen.labels)
        assertEquals("搜索\n购物车", screen.allText)
        assertEquals("搜索 购物车", screen.wordsWithin("n1"))
        assertEquals("购物车", screen.wordsWithin("n2"))
        assertEquals("", screen.wordsWithin("missing"))
        assertEquals(ScreenText.EMPTY.labels, ScreenText.parse("Permission denied").labels)
    }

    @Test
    fun `steps are named in a few words the user can follow`() {
        val screen = ScreenText("x", mapOf("n1" to "蜜雪冰城(高新店) 4.8分 月售2000+ 起送¥15"))
        val name: (String) -> String = { if (it == "com.sankuai.meituan") "美团" else it }

        fun label(
            tool: String,
            vararg args: Pair<String, String>,
        ) = StepLabels.of(tool, buildJsonObject { args.forEach { (k, v) -> put(k, v) } }, screen, name)
        assertEquals("打开「美团」", label("open_app", "package_id" to "com.sankuai.meituan"))
        assertEquals("点「蜜雪冰城(高新店) 4.8分 月…」", label("click_node", "node_id" to "n1"))
        assertEquals("点一下", label("click_node", "node_id" to "gone"))
        assertEquals("输入「冰鲜柠檬水」", label("type_append_text", "text" to "冰鲜柠檬水"))
        assertEquals("输入「少冰」", label("type_replace_text", "new_text" to "少冰"))
        assertEquals("返回", label("press_back"))
        assertEquals("看屏幕", label("get_screen_state"))
        assertEquals("some_new_tool", label("some_new_tool"))
    }

    @Test
    fun `an app answered always is remembered, once only lasts the task, no is not remembered`() {
        val remembered = mutableMapOf<String, Boolean>()
        val rules = AppGateRules({ remembered[it] }, { remembered[it] = true })
        assertEquals(null, rules.settled("a"))
        assertTrue(rules.apply("a", AppAnswer.ONCE))
        assertEquals(true, rules.settled("a"))
        rules.newTask()
        assertEquals(null, rules.settled("a"))
        assertTrue(rules.apply("b", AppAnswer.ALWAYS))
        rules.newTask()
        assertEquals(true, rules.settled("b"))
        assertFalse(rules.apply("c", AppAnswer.DENY))
        assertEquals(null, rules.settled("c"))
        remembered["d"] = false
        assertEquals(false, rules.settled("d"))
    }

    @TempDir
    lateinit var dir: File

    @Test
    fun `app permissions and the task history survive a restart`() {
        val grants = AppGrantsFile(File(dir, "agent/grants.json"))
        grants.set("com.a", true)
        grants.set("com.b", false)
        grants.set("com.a", null)
        assertEquals(mapOf("com.b" to false), AppGrantsFile(File(dir, "agent/grants.json")).all())

        val file = File(dir, "agent/history.json")
        val runs = AgentRuns(AgentHistoryFile(file))
        runs.begin(AgentRun("t1", "点奶茶", startedAt = 1))
        runs.update("t1") { it.copy(steps = it.steps + AgentStep("打开「美团」")) }
        assertTrue(runs.past.value.isEmpty())
        runs.finish("t1", RunStatus.DONE, "好了", endedAt = 9)
        runs.finish("t1", RunStatus.FAILED, "不该生效", endedAt = 10)
        runs.update("t1") { it.copy(result = "不该生效") }
        val reopened = AgentRuns(AgentHistoryFile(file))
        val saved = reopened.past.value.single()
        assertEquals(RunStatus.DONE, saved.status)
        assertEquals("好了", saved.result)
        assertEquals(listOf(AgentStep("打开「美团」")), saved.steps)
        assertEquals(saved, reopened.find("t1"))
        reopened.clearHistory()
        assertTrue(AgentHistoryFile(file).load().isEmpty())
    }

    @Test
    fun `the history keeps the newest tasks first and forgets the oldest beyond its limit`() {
        val history = AgentHistoryFile(File(dir, "h.json"))
        repeat(AgentHistoryFile.KEEP + 5) { history.record(AgentRun("t$it", "任务$it", RunStatus.DONE)) }
        val runs = history.load()
        assertEquals(AgentHistoryFile.KEEP, runs.size)
        assertEquals("t${AgentHistoryFile.KEEP + 4}", runs.first().id)
        assertEquals("t5", runs.last().id)
    }

    @Test
    fun `the notification says which step it is on, then the result`() {
        val running = AgentRun("t", "点奶茶", steps = listOf(AgentStep("打开「美团」"), AgentStep("点「搜索」")))
        assertEquals("正在操作手机", AgentNotices.title(running))
        assertEquals("第 2 步：点「搜索」", AgentNotices.text(running))
        assertEquals("点奶茶\n1. 打开「美团」\n2. 点「搜索」", AgentNotices.detail(running))
        assertEquals("点奶茶", AgentNotices.text(running.copy(steps = emptyList())))
        val over = running.copy(status = RunStatus.HANDED_OVER, result = "到付款这一步了")
        assertEquals("需要你接手", AgentNotices.title(over))
        assertEquals("到付款这一步了", AgentNotices.text(over))
    }

    @Test
    fun `a reply in tool-call markup is read as the move it means, and JSON wins when both are there`() {
        val markup =
            "<tool_call><function=click_node><parameter=node_id>node_8beb</parameter></function></tool_call>"
        val click = AgentMove.Call("click_node", buildJsonObject { put("node_id", "node_8beb") })
        assertEquals(click, AgentProtocol.parse(markup))
        val plan =
            "<tool_call><function=plan><parameter=steps>[\"打开分类\", \"记下商品\"]</parameter></function></tool_call>"
        assertEquals(AgentMove.Plan(listOf("打开分类", "记下商品")), AgentProtocol.parse(plan))
        val flag = "<function=get_screen_state><parameter=include_screenshot>false</parameter></function>"
        val call = AgentProtocol.parse(flag) as AgentMove.Call
        assertEquals(JsonPrimitive(false), call.args["include_screenshot"])
        val both = """{"action":"press_back","args":{}}<tool_call><function=press_home></function></tool_call>"""
        assertEquals(AgentMove.Call("press_back", buildJsonObject { }), AgentProtocol.parse(both))
    }
}
