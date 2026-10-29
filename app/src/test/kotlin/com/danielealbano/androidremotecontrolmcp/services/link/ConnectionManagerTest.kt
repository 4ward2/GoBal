package com.danielealbano.androidremotecontrolmcp.services.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ConnectionManager")
class ConnectionManagerTest {
    private class Lock : WakeLockLike {
        var held = false
        var acquired = 0
        var released = 0

        override fun acquire(timeoutMs: Long) {
            held = true
            acquired++
        }

        override fun release() {
            held = false
            released++
        }
    }

    private class Link(
        override val id: String,
        var answer: Boolean? = true,
    ) : ManagedLink {
        override val label = "电脑 $id"
        override val online = MutableStateFlow(false)
        override val beats get() = answer != null
        var opened = 0
        var closed = 0
        var asked = 0

        override fun open() {
            opened++
        }

        override fun close() {
            closed++
            online.value = false
        }

        override suspend fun beat(): Boolean? {
            asked++
            return answer
        }
    }

    private class Phone(
        private val test: TestScope,
    ) {
        val net = MutableStateFlow(Net(up = true, kind = "wifi"))
        var watching = false
        var timer: Long? = null
        var timersSet = 0
        var shown: List<String>? = null
        val working = Lock()
        val arriving = Lock()

        private val network =
            object : NetworkWatch {
                override val net = this@Phone.net

                override fun start() {
                    watching = true
                }

                override fun stop() {
                    watching = false
                }
            }
        private val ticker =
            object : Ticker {
                override fun after(delayMs: Long) {
                    timer = test.testScheduler.currentTime + delayMs
                    timersSet++
                }

                override fun cancel() {
                    timer = null
                }
            }
        private val host =
            object : OnlineHost {
                override fun show(labels: List<String>) {
                    shown = labels
                }

                override fun hide() {
                    shown = null
                }
            }
        val manager =
            ConnectionManager(
                LinkParts(network, ticker, host, ShortWake(working, arriving)),
                test.backgroundScope,
                { test.testScheduler.currentTime },
                Random(1),
            )

        /** Lets the time pass until the timer is due, and fires it. */
        suspend fun fire() {
            val due = checkNotNull(timer)
            test.advanceTimeBy(due - test.testScheduler.currentTime)
            timer = null
            manager.tick()
            test.runCurrent()
        }
    }

    @Test
    fun `with nothing to keep there is no connection, no service, no timer and no network watch`() =
        runTest {
            val phone = Phone(this)
            phone.manager.apply(Wanted())
            runCurrent()
            assertFalse(phone.watching)
            assertNull(phone.timer)
            assertNull(phone.shown)
            assertEquals(0, phone.working.acquired + phone.arriving.acquired)
        }

    @Test
    fun `links are opened once, shown while kept in the background, and closed when no longer wanted`() =
        runTest {
            val phone = Phone(this)
            val link = Link("a")
            phone.manager.apply(Wanted(listOf(link)))
            phone.manager.apply(Wanted(listOf(link)))
            runCurrent()
            assertEquals(1, link.opened)
            assertEquals(listOf("电脑 a"), phone.shown)
            assertTrue(phone.watching)
            // Switched off and not on screen: everything goes.
            phone.manager.apply(Wanted(listOf(link), background = false, visible = false))
            assertEquals(1, link.closed)
            assertNull(phone.shown)
            assertFalse(phone.watching)
            assertNull(phone.timer)
        }

    @Test
    fun `switched off, the links live only while the app is on screen, and without a notification`() =
        runTest {
            val phone = Phone(this)
            val link = Link("a")
            phone.manager.apply(Wanted(listOf(link), background = false, visible = true))
            assertEquals(1, link.opened)
            assertNull(phone.shown)
        }

    @Test
    fun `no timer runs while no link is online`() =
        runTest {
            val phone = Phone(this)
            val link = Link("a")
            phone.manager.apply(Wanted(listOf(link)))
            runCurrent()
            assertNull(phone.timer)
            link.online.value = true
            runCurrent()
            assertEquals(HeartbeatPacer.STEPS_MS.first(), phone.timer)
            link.online.value = false
            runCurrent()
            assertNull(phone.timer)
        }

    @Test
    fun `a long poll alone sets no timer at all`() =
        runTest {
            val phone = Phone(this)
            val poll = Link("poll", answer = null)
            phone.manager.apply(Wanted(listOf(poll)))
            poll.online.value = true
            runCurrent()
            assertNull(phone.timer)
            assertEquals(0, phone.timersSet)
            phone.net.value = Net(up = true, kind = "cellular")
            runCurrent()
            assertNull(phone.timer)
        }

    @Test
    fun `one wake-up asks every online link, and the lock is let go afterwards`() =
        runTest {
            val phone = Phone(this)
            val links = listOf(Link("a"), Link("b"), Link("poll", answer = null))
            phone.manager.apply(Wanted(links))
            links.forEach { it.online.value = true }
            runCurrent()
            phone.fire()
            assertEquals(listOf(1, 1, 0), links.map { it.asked })
            assertEquals(1, phone.working.acquired)
            assertEquals(1, phone.working.released)
            assertFalse(phone.working.held)
        }

    @Test
    fun `the interval grows while the answers come and comes down when one does not`() =
        runTest {
            val phone = Phone(this)
            val link = Link("a")
            phone.manager.apply(Wanted(listOf(link)))
            link.online.value = true
            runCurrent()
            val steps = HeartbeatPacer.STEPS_MS
            repeat(3) { phone.fire() }
            assertEquals(steps[1], checkNotNull(phone.timer) - testScheduler.currentTime)
            // A link that does not answer reconnects, and its channel reports the loss.
            link.answer = false
            phone.fire()
            phone.manager.pause(0)
            phone.fire()
            assertEquals(steps[0], checkNotNull(phone.timer) - testScheduler.currentTime)
        }

    @Test
    fun `the lock is let go even when a heartbeat throws`() =
        runTest {
            val phone = Phone(this)
            val broken =
                object : ManagedLink {
                    override val id = "x"
                    override val label = "x"
                    override val online = MutableStateFlow(true)

                    override fun open() = Unit

                    override fun close() = Unit

                    override suspend fun beat(): Boolean? = error("boom")
                }
            phone.manager.apply(Wanted(listOf(broken)))
            runCurrent()
            advanceTimeBy(HeartbeatPacer.STEPS_MS.first())
            assertTrue(runCatching { phone.manager.tick() }.isFailure)
            assertFalse(phone.working.held)
            assertEquals(phone.working.acquired, phone.working.released)
        }

    @Test
    fun `a retry waits longer after each failure, and not at all once the network comes back`() =
        runTest {
            val phone = Phone(this)
            phone.manager.apply(Wanted(listOf(Link("a"))))
            runCurrent()
            val waits =
                (1..4).map { failures ->
                    val before = testScheduler.currentTime
                    phone.manager.pause(failures)
                    testScheduler.currentTime - before
                }
            assertTrue(waits[0] in 1_000L..2_000L)
            assertTrue(waits[3] in 8_000L..16_000L)
            // No network: the wait is for the network, however long, and ends the moment it is back.
            phone.net.value = Net(up = false)
            runCurrent()
            assertNull(phone.timer)
            var resumed = false
            val waiting =
                launch {
                    phone.manager.pause(6)
                    resumed = true
                }
            advanceTimeBy(3_600_000)
            assertFalse(resumed)
            phone.net.value = Net(up = true, kind = "cellular")
            runCurrent()
            assertTrue(resumed)
            waiting.join()
        }

    @Test
    fun `a network change ends a wait in progress`() =
        runTest {
            val phone = Phone(this)
            phone.manager.apply(Wanted(listOf(Link("a"))))
            runCurrent()
            var resumed = false
            launch {
                phone.manager.pause(8)
                resumed = true
            }
            advanceTimeBy(1_000)
            assertFalse(resumed)
            phone.net.value = Net(up = true, kind = "cellular")
            runCurrent()
            assertTrue(resumed)
            assertTrue(phone.arriving.acquired > 0)
        }

    @Test
    fun `the pacer and the backoff keep within their bounds`() {
        val pacer = HeartbeatPacer()
        repeat(200) { pacer.succeeded() }
        assertEquals(HeartbeatPacer.STEPS_MS.last(), pacer.intervalMs)
        pacer.failed()
        val lowered = pacer.intervalMs
        repeat(19) { pacer.succeeded() }
        assertEquals(lowered, pacer.intervalMs)
        pacer.succeeded()
        assertEquals(HeartbeatPacer.STEPS_MS.last(), pacer.intervalMs)
        repeat(50) { pacer.failed() }
        assertEquals(HeartbeatPacer.STEPS_MS.first(), pacer.intervalMs)
        val random = Random(7)
        assertEquals(0, Backoff.delayMs(0, random))
        val waits = (1..40).map { Backoff.delayMs(it, random) }
        assertTrue(waits.all { it in 1_000L..300_000L })
        assertTrue(waits.drop(10).all { it >= 150_000L })
        assertTrue(waits.drop(10).toSet().size > 1)
    }
}
