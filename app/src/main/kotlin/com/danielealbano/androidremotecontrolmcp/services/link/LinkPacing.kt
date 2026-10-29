package com.danielealbano.androidremotecontrolmcp.services.link

import kotlin.random.Random

/**
 * How often an idle connection is asked whether the far end is still there. A phone network
 * drops a connection that says nothing for too long, and how long that is differs from network
 * to network: the interval starts short, grows while the answers keep coming, and comes back
 * down when a connection is found dead. Fewer questions is less radio time, which is what a
 * connection kept all day costs. Pure.
 */
class HeartbeatPacer(
    private val stepsMs: List<Long> = STEPS_MS,
) {
    private var step = 0
    private var ceiling = stepsMs.lastIndex
    private var answered = 0

    /** How long to wait before the next heartbeat. */
    val intervalMs: Long get() = stepsMs[step]

    /** The far end answered. */
    fun succeeded() {
        answered++
        val mayClimb = step < ceiling && answered >= CLIMB_AFTER
        // An interval that failed once is tried again only after a long quiet run: networks change.
        val mayRetry = step == ceiling && ceiling < stepsMs.lastIndex && answered >= RETRY_CEILING_AFTER
        if (mayRetry) ceiling++
        if (mayClimb || mayRetry) {
            step++
            answered = 0
        }
    }

    /** The connection was found dead: this interval is too long here. */
    fun failed() {
        // At the shortest interval a loss says nothing about the interval: nothing is learnt from it.
        if (step > 0) {
            step--
            ceiling = step
        }
        answered = 0
    }

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val CLIMB_AFTER = 3
        private const val RETRY_CEILING_AFTER = 20

        /** One to nine and a half minutes: under the ten after which many carriers forget a connection. */
        val STEPS_MS: List<Long> = listOf(2L, 4L, 6L, 9L, 12L, 15L, 19L).map { it * MINUTE_MS / 2 }
    }
}

/**
 * How long to wait before trying a lost connection again: twice as long after every failure in
 * a row, up to a ceiling, and never the same wait for two phones (or two connections) that lost
 * the same server at the same moment. Pure.
 */
object Backoff {
    private const val FIRST_MS = 2_000L
    private const val CEILING_MS = 5 * 60_000L
    private const val MAX_DOUBLINGS = 16

    /** The wait after [failures] failed attempts in a row; none after a connection that had worked. */
    fun delayMs(
        failures: Int,
        random: Random,
    ): Long {
        if (failures <= 0) return 0
        val doubled = FIRST_MS shl (failures - 1).coerceAtMost(MAX_DOUBLINGS)
        val full = doubled.coerceAtMost(CEILING_MS)
        // Between half of it and all of it.
        return full / 2 + random.nextLong(full / 2 + 1)
    }
}
