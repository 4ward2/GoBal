package com.danielealbano.androidremotecontrolmcp.services.remote.hooks

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatApiResult
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelGateway
import com.danielealbano.androidremotecontrolmcp.services.remote.AgentSession
import com.danielealbano.androidremotecontrolmcp.services.remote.PairedComputer
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteAgent
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteEvent
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class GoalRunnerTest {
    @TempDir
    lateinit var dir: File

    private val computer = PairedComputer(id = "pc", name = "电脑", backend = "paseo", protocol = 2)
    private val idle = RemoteAgent("a1", "测试代理", "claude", "", "", "idle")
    private val running = idle.copy(status = "running")
    private val session = mockk<AgentSession>(relaxed = true)
    private val gateway = mockk<ModelGateway>()
    private val request = GoalRequest("让测试通过", "测试全部通过并贴出结果", maxRounds = 3, hours = 1)

    private fun runner(): GoalRunner {
        val context = mockk<Context> { every { filesDir } returns dir }
        val computers = mockk<RemoteComputers> { every { session("pc") } returns session }
        val model = flowOf(ModelChoice("p", "m"))
        val settings = mockk<AssistSettingsRepository> { every { assistantModel } returns model }
        coEvery { session.send(any(), any(), any()) } returns Result.success(Unit)
        return GoalRunner(context, computers, gateway, settings, UnconfinedTestDispatcher())
    }

    private fun replies(text: String) {
        coEvery { session.history("a1") } returns Result.success(listOf(RemoteItem.Assistant(text, "m1")))
    }

    private fun judged(answer: String) {
        coEvery { gateway.chat(any(), any(), any()) } returns ChatApiResult(ok = true, content = answer)
    }

    private fun GoalRunner.round(after: RemoteAgent = idle) {
        took(computer, RemoteEvent.AgentChanged(running))
        took(computer, RemoteEvent.AgentChanged(after))
    }

    @Test
    fun `starting tells the agent the goal and the criteria`() =
        runTest {
            val goals = runner()
            assertTrue(goals.start(computer, idle, request).isSuccess)
            coVerify { session.send("a1", match { "让测试通过" in it && "测试全部通过" in it }, any()) }
            assertEquals(
                GoalState.WORKING,
                goals.goals.value
                    .single()
                    .state,
            )
        }

    @Test
    fun `a round that misses the criteria is told what is missing`() =
        runTest {
            val goals = runner()
            goals.start(computer, idle, request)
            replies("改了两个文件")
            judged("{\"done\": false, \"missing\": \"没有贴测试结果\"}")
            goals.round()
            val run = goals.goals.value.single()
            assertEquals(2, run.round)
            assertEquals(GoalState.WORKING, run.state)
            coVerify { session.send("a1", match { "第2轮" in it && "没有贴测试结果" in it }, any()) }
        }

    @Test
    fun `a round that meets the criteria ends the goal`() =
        runTest {
            val goals = runner()
            goals.start(computer, idle, request)
            replies("12 passed")
            judged("好的 {\"done\": true, \"missing\": \"\"}")
            goals.round()
            assertEquals(
                GoalState.DONE,
                goals.goals.value
                    .single()
                    .state,
            )
        }

    @Test
    fun `the rounds run out`() =
        runTest {
            val goals = runner()
            goals.start(computer, idle, request.copy(maxRounds = 1))
            replies("还没好")
            judged("{\"done\": false, \"missing\": \"测试还挂着\"}")
            goals.round()
            val run = goals.goals.value.single()
            assertEquals(GoalState.OUT_OF_ROUNDS, run.state)
            assertEquals("测试还挂着", run.missing)
        }

    @Test
    fun `an interrupted round is not judged and waits for the agent to carry on`() =
        runTest {
            val goals = runner()
            goals.start(computer, idle, request)
            replies("API Error: 5-hour limit reached ∙ resets 3pm")
            goals.round()
            assertEquals(
                GoalState.INTERRUPTED,
                goals.goals.value
                    .single()
                    .state,
            )
            coVerify(exactly = 0) { gateway.chat(any(), any(), any()) }
            goals.took(computer, RemoteEvent.AgentChanged(running))
            assertEquals(
                GoalState.WORKING,
                goals.goals.value
                    .single()
                    .state,
            )
        }

    @Test
    fun `a stopped goal ignores later rounds`() =
        runTest {
            val goals = runner()
            goals.start(computer, idle, request)
            goals.stop(
                goals.goals.value
                    .single()
                    .id,
            )
            goals.round()
            assertEquals(
                GoalState.STOPPED,
                goals.goals.value
                    .single()
                    .state,
            )
            coVerify(exactly = 0) { gateway.chat(any(), any(), any()) }
        }

    @Test
    fun `a busy agent cannot be given a goal`() =
        runTest {
            val goals = runner()
            assertTrue(goals.start(computer, running, request).isFailure)
            assertTrue(goals.goals.value.isEmpty())
        }

    @Test
    fun `goals survive a restart`() =
        runTest {
            runner().start(computer, idle, request)
            val again = runner().goals.value.single()
            assertEquals("让测试通过", again.goal)
            assertTrue(again.state.active)
        }

    @Test
    fun `the check's answer is read only when it is the json asked for`() {
        assertEquals(Verdict(true, ""), GoalJudge.verdict("```json\n{\"done\": true}\n```"))
        assertFalse(GoalJudge.verdict("{\"done\": false}")!!.missing.isBlank())
        assertNull(GoalJudge.verdict("达成了"))
        assertNull(GoalJudge.verdict("{\"missing\": \"x\"}"))
    }

    @Test
    fun `the check is told the round and what was missing last time`() {
        val run = GoalRun("g", "pc", "电脑", "a1", "代理", "目标", "标准", 3, 0, 0, 0, round = 2, missing = "没贴结果")
        val asked = GoalJudge.messages(run, "回复").last().text
        assertTrue("第 2 轮" in asked && "没贴结果" in asked)
        val first = GoalJudge.messages(run.copy(round = 1, missing = ""), "回复").last().text
        assertTrue("第一轮" in first)
    }

    @Test
    fun `the last reply joins the pieces of the last message`() {
        val items =
            listOf(
                RemoteItem.Assistant("旧的", "m0"),
                RemoteItem.User("继续", "u1"),
                RemoteItem.Assistant("全部", "m1"),
                RemoteItem.Assistant("通过", "m1"),
            )
        assertEquals("全部通过", GoalJudge.lastReply(items))
    }
}
