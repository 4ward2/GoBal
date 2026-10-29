package com.danielealbano.androidremotecontrolmcp.services.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** A connection the app keeps while it is wanted online: to a paired computer, or a message channel. */
interface ManagedLink {
    val id: String

    /** What the notification calls it. */
    val label: String

    /** True while it is connected. */
    val online: StateFlow<Boolean>

    /** False for a link that keeps itself alive (a long poll): it is never asked, and no timer runs for it. */
    val beats: Boolean get() = true

    fun open()

    fun close()

    /**
     * Asks the far end whether it is still there. Null when the link needs no asking (a long
     * poll is its own heartbeat); false when nothing answered, after which the link reconnects.
     */
    suspend fun beat(): Boolean?
}

/** Whether the phone has a network, and of which kind ("wifi", "cellular", …). */
data class Net(
    val up: Boolean,
    val kind: String = "",
)

interface NetworkWatch {
    val net: StateFlow<Net>

    fun start()

    fun stop()
}

/** Wakes the manager once, some time from now, even when the phone sleeps. One at a time. */
interface Ticker {
    fun after(delayMs: Long)

    fun cancel()
}

/** What shows the user that the app stays connected (a foreground service and its notification). */
interface OnlineHost {
    fun show(labels: List<String>)

    fun hide()
}

/** What the manager is asked to keep. */
data class Wanted(
    val links: List<ManagedLink> = emptyList(),
    /** The user's switch: stay connected when the app is not on screen. */
    val background: Boolean = true,
    /** The app is on screen (or was a moment ago). */
    val visible: Boolean = false,
)

/** The system's parts the manager works with. */
class LinkParts(
    val network: NetworkWatch,
    val ticker: Ticker,
    val host: OnlineHost,
    val wake: ShortWake,
)

/** When a channel that lost its far end may try again. */
fun interface Reconnects {
    /** Returns when the next attempt may be made; [failures] is how many were made in vain in a row. */
    suspend fun pause(failures: Int)
}

/** The links being kept, each followed for whether it is online. */
private class KeptLinks(
    private val scope: CoroutineScope,
    private val onlineChanged: (Boolean) -> Unit,
) {
    private val open = LinkedHashMap<String, ManagedLink>()
    private val watching = HashMap<String, Job>()

    val all: List<ManagedLink> get() = open.values.toList()

    /** Opens the links of [keep] that are not open, closes the open ones that are not in it. */
    fun becomes(keep: List<ManagedLink>) {
        val ids = keep.map { it.id }.toSet()
        open.keys.filterNot { it in ids }.forEach { id ->
            watching.remove(id)?.cancel()
            open.remove(id)?.close()
        }
        keep.filterNot { it.id in open }.forEach { link ->
            open[link.id] = link
            link.open()
            // Coming online starts the heartbeats; going offline lets the timer rest.
            watching[link.id] = scope.launch { link.online.collect { onlineChanged(it) } }
        }
    }
}

/**
 * The one place that decides when the app talks to the network while nobody is looking. Every
 * background connection is registered here, so that:
 *
 *  - with nothing paired and no channel on, nothing at all is kept: no connection, no service,
 *    no timer;
 *  - all heartbeats go out on one wake-up, at an interval that adapts to the network;
 *  - a lost connection is retried with growing, jittered waits, at once when the network comes
 *    back, and not at all while there is no network;
 *  - the processor is held awake only while a heartbeat or a retry is being made.
 */
class ConnectionManager(
    private val parts: LinkParts,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val random: Random = Random.Default,
    /** One line per heartbeat, retry and network change, for whoever reads the log; nothing private goes in. */
    private val log: (String) -> Unit = {},
) : Reconnects {
    private val links = KeptLinks(scope, ::onlineChanged)
    private val pacers = HashMap<String, HeartbeatPacer>()
    private val nudges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var network: Job? = null
    private var hosted = false
    private var beatAt = NEVER
    private var retryAt = NEVER

    /** The links being kept right now. */
    val kept: List<ManagedLink> @Synchronized get() = links.all

    private val pacer: HeartbeatPacer
        get() = pacers.getOrPut(parts.network.net.value.kind) { HeartbeatPacer() }

    /** Brings what is kept in line with [wanted]. Safe to call as often as anything changes. */
    @Synchronized
    fun apply(wanted: Wanted) {
        val keep = if (wanted.background || wanted.visible) wanted.links else emptyList()
        links.becomes(keep)
        if (keep.isEmpty()) stop() else start()
        // The notification is for staying connected unseen; an app on screen needs none.
        val host = keep.isNotEmpty() && wanted.background
        if (host) {
            parts.host.show(keep.map { it.label })
        } else if (hosted) {
            parts.host.hide()
        }
        hosted = host
    }

    private fun start() {
        if (network != null) return
        parts.network.start()
        network = scope.launch { parts.network.net.collect(::networkChanged) }
    }

    private fun stop() {
        network?.cancel()
        network = null
        parts.network.stop()
        parts.ticker.cancel()
        beatAt = NEVER
        retryAt = NEVER
    }

    @Synchronized
    private fun onlineChanged(online: Boolean) {
        val toAsk = links.all.any { it.online.value && it.beats }
        if (online && toAsk && beatAt == NEVER) beatAt = clock() + pacer.intervalMs
        schedule()
    }

    @Synchronized
    private fun networkChanged(net: Net) {
        log("network: " + if (net.up) net.kind else "none")
        if (net.up) {
            // A new network is a new start: whoever waits to retry tries now.
            parts.wake.touch()
            nudges.tryEmit(Unit)
            if (links.all.any { it.online.value && it.beats }) beatAt = clock() + pacer.intervalMs
            schedule()
        } else {
            // No network: nothing to ask and nothing to try, so nothing to wake up for.
            parts.ticker.cancel()
        }
    }

    /** The timer went off: one wake-up for every heartbeat and every retry that is due. */
    suspend fun tick() {
        if (!parts.network.net.value.up) return
        parts.wake.during {
            val now = clock()
            val retryDue = synchronized(this) { (now >= retryAt).also { due -> if (due) retryAt = NEVER } }
            if (retryDue) {
                parts.wake.touch()
                nudges.tryEmit(Unit)
            }
            if (now >= beatAt) heartbeat()
        }
        synchronized(this) { schedule() }
    }

    private suspend fun heartbeat() {
        val asked = kept.filter { it.online.value && it.beats }
        val answers = coroutineScope { asked.map { link -> link to link.beat() } }.mapNotNull { it.second }
        synchronized(this) {
            // A link that did not answer reconnects, and reports the loss through [pause]: that
            // is where the interval comes down, once, whichever way the loss was noticed.
            if (answers.isNotEmpty() && answers.all { it }) pacer.succeeded()
            beatAt = clock() + pacer.intervalMs
            val next = pacer.intervalMs / SECOND_MS
            log("heartbeat: asked ${answers.size}, answered ${answers.count { it }}, next in ${next}s")
        }
    }

    /** Sets the one timer to whichever comes first, a heartbeat or a retry; none when neither is due. */
    private fun schedule() {
        val anyOnline = links.all.any { it.online.value && it.beats }
        if (!anyOnline) beatAt = NEVER
        val next = minOf(beatAt, retryAt)
        val up = parts.network.net.value.up
        val wait = (next - clock()).coerceAtLeast(MIN_TICK_MS)
        if (next == NEVER || !up) parts.ticker.cancel() else parts.ticker.after(wait)
    }

    override suspend fun pause(failures: Int) {
        val wasDown = !parts.network.net.value.up
        // Without a network there is nothing to try: wait for one, however long, without waking.
        parts.network.net.first { it.up }
        val wait = if (wasDown) 0 else Backoff.delayMs(failures, random)
        if (failures == 0 && !wasDown) lostWhileOnline()
        log("retry: after $failures failures, waiting ${wait}ms" + if (wasDown) " (network was down)" else "")
        if (wait > 0) {
            synchronized(this) {
                retryAt = minOf(retryAt, clock() + wait)
                schedule()
            }
            // Whichever comes first: the wait runs out, the timer fires, or the network changes.
            withTimeoutOrNull(wait) { nudges.first() }
        }
    }

    /**
     * A connection that worked was lost with the network still up. It may have been the heartbeat
     * interval (the network forgot the idle connection), so the interval comes down a step; if it
     * was the far end going away instead, the price is only a few more heartbeats.
     */
    @Synchronized
    private fun lostWhileOnline() {
        pacer.failed()
        if (beatAt != NEVER) beatAt = minOf(beatAt, clock() + pacer.intervalMs)
    }

    private companion object {
        const val NEVER = Long.MAX_VALUE
        const val MIN_TICK_MS = 1_000L
        const val SECOND_MS = 1_000L
    }
}
