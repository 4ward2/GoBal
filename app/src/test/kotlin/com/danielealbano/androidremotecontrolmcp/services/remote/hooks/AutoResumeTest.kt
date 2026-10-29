package com.danielealbano.androidremotecontrolmcp.services.remote.hooks

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import com.danielealbano.androidremotecontrolmcp.services.remote.AgentSession
import com.danielealbano.androidremotecontrolmcp.services.remote.PairedComputer
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteAgent
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoResumeTest {
    private val computer = PairedComputer(id = "pc", name = "电脑", backend = "paseo", protocol = 2)
    private val crash = "Claude stopped unexpectedly (exit code 1)"
    private val crashed = RemoteAgent("a1", "测试代理", "claude", "", "", "idle", lastError = crash)
    private val running = crashed.copy(status = "running", lastError = "")
    private val agents = MutableStateFlow(listOf(crashed))
    private val session =
        mockk<AgentSession>(relaxed = true) {
            every { agents } returns this@AutoResumeTest.agents
            coEvery { send(any(), any(), any()) } returns Result.success(Unit)
        }

    private fun TestScope.resume(on: Boolean): AutoResume {
        val store =
            mockk<DataStore<Preferences>> {
                every { data } returns flowOf(preferencesOf(booleanPreferencesKey("remote_auto_resume") to on))
            }
        val computers = mockk<RemoteComputers> { every { session("pc") } returns session }
        return AutoResume(store, computers, StandardTestDispatcher(testScheduler))
    }

    private fun AutoResume.crash() {
        took(computer, RemoteEvent.AgentChanged(running))
        took(computer, RemoteEvent.AgentChanged(crashed))
    }

    @Test
    fun `a crashed agent is told to carry on after the wait`() =
        runTest {
            val hook = resume(on = true)
            hook.crash()
            advanceTimeBy(5_000)
            coVerify(exactly = 0) { session.send(any(), any(), any()) }
            advanceUntilIdle()
            coVerify { session.send("a1", match { "[自动续跑]" in it && "程序意外退出" in it }, any()) }
            assertEquals(
                ResumeOutcome.RESUMED,
                hook.log.value
                    .first()
                    .outcome,
            )
        }

    @Test
    fun `nothing is sent when the user spoke to the agent meanwhile`() =
        runTest {
            val hook = resume(on = true)
            hook.crash()
            agents.value = listOf(crashed.copy(lastUserMessageAt = 1_000))
            advanceUntilIdle()
            coVerify(exactly = 0) { session.send(any(), any(), any()) }
            assertEquals(
                ResumeOutcome.USER_TOOK_OVER,
                hook.log.value
                    .first()
                    .outcome,
            )
        }

    @Test
    fun `nothing happens while switched off`() =
        runTest {
            val hook = resume(on = false)
            hook.crash()
            advanceUntilIdle()
            coVerify(exactly = 0) { session.send(any(), any(), any()) }
            assertEquals(emptyList<ResumeEntry>(), hook.log.value)
        }

    @Test
    fun `an error that does not go away by itself is left alone`() =
        runTest {
            val hook = resume(on = true)
            hook.took(computer, RemoteEvent.AgentChanged(running))
            hook.took(computer, RemoteEvent.AgentChanged(crashed.copy(lastError = "403 Forbidden")))
            advanceUntilIdle()
            coVerify(exactly = 0) { session.send(any(), any(), any()) }
        }
}
