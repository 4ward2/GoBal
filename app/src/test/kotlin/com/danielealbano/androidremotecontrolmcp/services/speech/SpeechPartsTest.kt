package com.danielealbano.androidremotecontrolmcp.services.speech

import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRun
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentStep
import com.danielealbano.androidremotecontrolmcp.services.agent.RunStatus
import com.danielealbano.androidremotecontrolmcp.services.call.HeardSegment
import com.danielealbano.androidremotecontrolmcp.services.call.SegmentTracker
import com.danielealbano.androidremotecontrolmcp.services.call.SttEvents
import com.danielealbano.androidremotecontrolmcp.ui.chat.TaskCardSteps
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.PhoneTasks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.ServerSocket

@DisplayName("In-app speech parts and phone-task messages")
class SpeechPartsTest {
    @Test
    fun `an utterance ends after speech followed by enough quiet, never before any speech`() {
        val tracker = SilenceTracker(speechLevel = 400, silenceMs = 1_200)
        assertFalse(tracker.finished(level = 50, nowMs = 0))
        assertFalse(tracker.finished(level = 50, nowMs = 5_000))
        assertFalse(tracker.heard)
        assertFalse(tracker.finished(level = 900, nowMs = 5_200))
        assertTrue(tracker.heard)
        assertFalse(tracker.finished(level = 60, nowMs = 5_400))
        assertFalse(tracker.finished(level = 60, nowMs = 6_500))
        assertTrue(tracker.finished(level = 60, nowMs = 6_600))
    }

    @Test
    fun `speaking again restarts the quiet period`() {
        val tracker = SilenceTracker(speechLevel = 400, silenceMs = 1_000)
        tracker.finished(900, 0)
        assertFalse(tracker.finished(10, 200))
        assertFalse(tracker.finished(900, 900))
        assertFalse(tracker.finished(10, 1_500))
        assertTrue(tracker.finished(10, 2_500))
    }

    @Test
    fun `the helper of rooted phones is only waited for when it can help`() {
        val unknown = SttRoute(voskUp = false, helperGone = false, inAppReady = false, chinese = true)
        assertTrue(unknown.shouldStartHelper())
        assertFalse(unknown.copy(voskUp = true).shouldStartHelper())
        assertFalse(unknown.copy(helperGone = true).shouldStartHelper())
        assertFalse(unknown.copy(inAppReady = true).shouldStartHelper())
        // The in-app recogniser only knows Chinese: for another language the helper is still worth a try.
        assertTrue(unknown.copy(inAppReady = true, chinese = false).shouldStartHelper())
    }

    @Test
    fun `a message is read sentence by sentence, without its markdown marks or status tag`() {
        val text = "**好的**，我来看看。\n- 第一点\n- 第二点！<status>认真中</status>"
        assertEquals(listOf("好的，我来看看。", "第一点", "第二点！"), ReadingText.sentences(text))
        assertTrue(ReadingText.sentences("  ").isEmpty())
    }

    @Test
    fun `samples become floats and a loudness reading`() {
        val pcm = shortArrayOf(16384, -16384, 0, 32767)
        assertEquals(listOf(0.5f, -0.5f, 0f), Pcm.toFloats(pcm, 3).toList())
        assertEquals(16384 * 2 / 3, Pcm.level(pcm, 3))
        assertEquals(0, Pcm.level(pcm, 0))
    }

    @Test
    fun `a port is open only while something listens on it`() {
        val server = ServerSocket(0)
        val port = server.localPort
        assertTrue(LocalPorts.isOpen(port))
        server.close()
        assertFalse(LocalPorts.isOpen(port))
    }

    private class Heard : SttEvents {
        val partials = mutableListOf<String>()
        val finals = mutableListOf<HeardSegment>()

        override fun onPartial(text: String) {
            partials += text
        }

        override fun onFinal(segment: HeardSegment) {
            finals += segment
        }

        override fun onError(message: String) = Unit
    }

    @Test
    fun `a sentence is reported as it grows and once more when it ends, timed from its first word`() {
        val heard = Heard()
        val tracker = SegmentTracker(heard)
        tracker.partial("", 100)
        tracker.partial("今天", 200)
        tracker.partial("今天", 300)
        tracker.partial("今天天气", 400)
        tracker.endOfSentence(900)
        assertEquals(listOf("今天", "今天天气"), heard.partials)
        assertEquals(listOf(HeardSegment("今天天气", 200, 900)), heard.finals)
        tracker.endOfSentence(1_000)
        assertEquals(1, heard.finals.size)
        tracker.partial("不错", 1_500)
        tracker.endOfSentence(2_000)
        assertEquals(HeardSegment("不错", 1_500, 2_000), heard.finals.last())
    }

    @Test
    fun `a task taken by the in-app agent becomes its card, a busy agent says so`() {
        val started = PhoneTasks.inApp("t7")
        assertEquals("t7", started.bubble.task)
        assertNull(started.error)
        val busy = PhoneTasks.inApp(null)
        assertNull(busy.bubble.task)
        assertEquals(PhoneTasks.BUSY, busy.error)
    }

    @Test
    fun `every task runs in the app, and with no model and no root the user is told what to choose`() {
        var asked = 0
        val agent = {
            asked++
            "t9"
        }
        val noModelNoRoot = PhoneTasks.start(noModel = true, rooted = false, agent = agent)
        assertNull(noModelNoRoot.bubble.task)
        assertTrue(noModelNoRoot.error.orEmpty().contains(PhoneTasks.NO_HELPER_HINT))
        assertEquals(0, asked)
        assertEquals("t9", PhoneTasks.start(noModel = true, rooted = true, agent = agent).bubble.task)
        assertEquals("t9", PhoneTasks.start(noModel = false, rooted = false, agent = agent).bubble.task)
        assertEquals(2, asked)
    }

    @Test
    fun `a finished task is written into the conversation for the model to read later`() {
        val run = AgentRun("t1", "点一杯奶茶", RunStatus.HANDED_OVER, result = "到付款这一步了")
        assertEquals("（手机操作「点一杯奶茶」：到付款这一步了）", PhoneTasks.record(run))
    }

    @Test
    fun `the task card lists the newest steps with their real numbers`() {
        val steps = (1..6).map { AgentStep("第${it}步", ok = it != 5) }
        val run = AgentRun("t1", "任务", steps = steps)
        assertEquals(2, TaskCardSteps.hidden(run))
        assertEquals(listOf("3. 第3步", "4. 第4步", "5. 第5步（没成功）", "6. 第6步"), TaskCardSteps.lines(run))
        val short = run.copy(steps = steps.take(2))
        assertEquals(0, TaskCardSteps.hidden(short))
        assertEquals(listOf("1. 第1步", "2. 第2步"), TaskCardSteps.lines(short))
    }
}
