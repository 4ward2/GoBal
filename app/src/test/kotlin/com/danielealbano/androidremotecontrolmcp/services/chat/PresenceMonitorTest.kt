package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PresenceMonitor")
class PresenceMonitorTest {
    private val good = ModelProvider("good", "好", ProviderKind.OPENAI, "https://g", "k", listOf("m"))
    private val bad = ModelProvider("bad", "坏", ProviderKind.OPENAI, "https://b", "k", listOf("m"))
    private val gateway = mockk<ModelGateway>()
    private val loopback = mockk<PhoneMcpLoopbackClient>()
    private val settings = mockk<AssistSettingsRepository> { every { providers } returns flowOf(listOf(good, bad)) }
    private val monitor = PresenceMonitor(gateway, loopback, settings).apply { pickAway = { Presence.BUSY } }
    private val onDevice = ModelChoice.ON_DEVICE
    private val viaGood = ModelChoice("good", "m")
    private val viaBad = ModelChoice("bad", "m")

    @Test
    fun `start-up probe marks every api, and runs only once`() =
        runTest {
            coEvery { gateway.probe(good) } returns true
            coEvery { gateway.probe(bad) } returns false
            every { loopback.status() } returns
                LoopbackStatus(httpOk = true, provider = ProviderSummary(configured = true, kind = "openai_compat"))
            assertEquals(Presence.CHECKING, monitor.presenceOf(onDevice))
            monitor.checkAllOnce()
            monitor.checkAllOnce()
            assertEquals(Presence.ONLINE, monitor.presenceOf(onDevice))
            assertEquals(Presence.ONLINE, monitor.presenceOf(viaGood))
            assertEquals(Presence.BUSY, monitor.presenceOf(viaBad))
            coVerify(exactly = 1) { gateway.probe(good) }
        }

    @Test
    fun `an unreachable loopback server leaves the on-device contact away`() =
        runTest {
            every { loopback.status() } throws IllegalStateException("down")
            coEvery { gateway.probe(any()) } returns false
            monitor.checkAll()
            assertTrue(monitor.presenceOf(onDevice) in PresenceMonitor.AWAY_STATES)
        }

    @Test
    fun `errors pick an away state once and keep it until a reply arrives`() {
        var picks = 0
        monitor.pickAway = {
            picks++
            Presence.INVISIBLE
        }
        monitor.markFailed(viaGood)
        monitor.markFailed(viaGood)
        assertEquals(Presence.INVISIBLE, monitor.presenceOf(viaGood))
        assertEquals(1, picks)
        assertEquals(Presence.CHECKING, monitor.presenceOf(viaBad))
        monitor.markAlive(viaGood)
        assertEquals(Presence.ONLINE, monitor.presenceOf(viaGood))
        monitor.markFailed(viaGood)
        assertEquals(2, picks)
    }

    @Test
    fun `only the online state counts as reachable`() {
        assertTrue(Presence.ONLINE.reachable)
        PresenceMonitor.AWAY_STATES.forEach { assertTrue(!it.reachable) }
        assertTrue(!Presence.CHECKING.reachable)
    }
}
