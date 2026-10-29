package com.danielealbano.androidremotecontrolmcp.services.agent

import com.danielealbano.androidremotecontrolmcp.services.chat.ChatApiResult
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatMessage
import com.danielealbano.androidremotecontrolmcp.services.skill.ScreenBlockers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Fewer captchas, and what the task does when one is put up anyway: cool the app down and open it again. */
@DisplayName("PhoneAgent captcha cooldown")
class CooldownTest {
    private val phone = FakePhone()
    private val events = Recorder()
    private val asked = mutableListOf<List<ChatMessage>>()
    private val replies = ArrayDeque<ChatApiResult>()
    private val waited = mutableListOf<Long>()

    /** The cooldowns among the waits: the short ones are the pause for a screen to settle. */
    private fun cooldowns() = waited.filter { it >= Cooldown.WAITS_MS.first() }

    private val app = "com.sankuai.meituan"
    private val captcha = FakePhone.screen(app, "y1" to "请向右滑动滑块验证")
    private val list = FakePhone.screen(app, "n1" to "拼好饭")

    @BeforeEach
    fun clean() = ColdStarts.clear()

    private fun says(vararg json: String) = json.forEach { replies += ChatApiResult(ok = true, content = it) }

    private suspend fun run(): AgentOutcome {
        val brain =
            AgentBrain { messages ->
                asked += messages
                replies.removeFirstOrNull() ?: ChatApiResult(ok = true, content = """{"action":"fail","args":{}}""")
            }
        val gate =
            object : AppGate {
                override fun settled(packageName: String): Boolean = true

                override suspend fun ask(packageName: String): Boolean = true
            }
        return PhoneAgent(phone, brain, gate, events, pause = { waited += it }).run("看看拼好饭")
    }

    private val tap = """{"action":"tap_text","args":{"text":"拼好饭"}}"""
    private val done = """{"action":"done","args":{"ok":true,"summary":"看完了"}}"""
    private val open = """{"action":"open_app","args":{"package_id":"com.sankuai.meituan"}}"""
    private val back = """{"action":"press_back","args":{}}"""

    @Test
    fun `a captcha is not solved, the app is closed, cooled down and opened again, and the task goes on`() =
        runTest {
            phone.screenText = captcha
            phone.screenAfterFirstRead = list
            says(tap, done)
            val outcome = run()
            assertEquals(RunStatus.DONE, outcome.status)
            assertEquals(listOf(60_000L), cooldowns())
            assertEquals(1, phone.called("press_home").size)
            assertEquals(app, (phone.called("close_app").single()["package_id"] as JsonPrimitive).content)
            assertEquals("true", (phone.called("open_app").single()["fresh"] as JsonPrimitive).content)
            val cooling = "「美团」弹出人机验证：先关掉它，冷却 1 分钟后重开（第 1/2 次）"
            assertTrue(events.steps.any { it.label == cooling })
            val told = asked.last().joinToString("\n") { it.text }
            assertTrue(told.contains("Do not try to get past a captcha yourself"))
            // The model is shown the screen the app came back on, not the captcha it was read off before.
            val shown = asked[1].last().text
            assertTrue(shown.contains("拼好饭"))
            assertFalse(shown.contains("请向右滑动滑块验证"))
        }

    @Test
    fun `a captcha still there after the last cooldown is the user's to do`() =
        runTest {
            phone.screenText = captcha
            says(tap, tap, tap)
            val outcome = run()
            assertEquals(AgentOutcome(RunStatus.HANDED_OVER, ScreenBlockers.CAPTCHA_REASON), outcome)
            assertEquals(Cooldown.WAITS_MS, cooldowns())
        }

    @Test
    fun `within a task an app starts from its first screen once, and is brought back after that`() =
        runTest {
            phone.screenText = list
            says(open, open, done)
            run()
            val fresh = phone.called("open_app").map { (it["fresh"] as JsonPrimitive).content }
            assertEquals(listOf("true", "false"), fresh)
        }

    @Test
    fun `an app is started cold at most three times in ten minutes, across tasks`() {
        assertTrue(ColdStarts.allow("a", now = 0))
        assertTrue(ColdStarts.allow("a", now = 1_000))
        ColdStarts.note("a", now = 2_000)
        assertFalse(ColdStarts.allow("a", now = 3_000))
        assertTrue(ColdStarts.allow("b", now = 3_000))
        assertTrue(ColdStarts.allow("a", now = 11 * 60_000L))
    }

    @Test
    fun `going back and forth is pointed out once, and when it goes on the task stops`() =
        runTest {
            phone.screenText = list
            says(*Array(12) { back })
            val outcome = run()
            assertEquals(AgentOutcome(RunStatus.FAILED, Thrash.STOP), outcome)
            assertEquals(1, asked.count { messages -> messages.last().text.contains("Harness: ${Thrash.WARN}") })
            // The twelfth is not made: the task stops before it.
            assertEquals(11, phone.called("press_back").size)
        }

    @Test
    fun `a saved skill stopped by a captcha is carried on step by step, after the app cooled down`() =
        runTest {
            phone.skillRun = "status: handed_over\n" + ScreenBlockers.CAPTCHA_REASON
            phone.screenText = captcha
            phone.screenAfterFirstRead = list
            says("""{"action":"get_screen_state","args":{}}""", done)
            val outcome = run()
            assertEquals(RunStatus.DONE, outcome.status)
            assertEquals(1, phone.called("close_app").size)
            assertTrue(asked.first()[1].text.contains(ScreenBlockers.CAPTCHA_REASON))
        }
}
