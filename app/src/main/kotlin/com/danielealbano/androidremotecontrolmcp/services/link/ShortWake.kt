package com.danielealbano.androidremotecontrolmcp.services.link

/** The system's wake lock, as much of it as is used here. */
interface WakeLockLike {
    /** Keeps the processor awake until [release], and never longer than [timeoutMs]. */
    fun acquire(timeoutMs: Long)

    fun release()
}

/**
 * Keeps the phone awake only for as long as a piece of work takes: asking the connections
 * whether they are alive, or taking in a message that just arrived. Nothing here is held while
 * the app merely waits; a lock that stays held is what drains a battery overnight.
 */
class ShortWake(
    private val working: WakeLockLike,
    private val arriving: WakeLockLike,
) {
    private var holders = 0

    /** Runs [block] with the processor kept awake, and lets go the moment it ends, however it ends. */
    suspend fun <T> during(block: suspend () -> T): T {
        enter()
        try {
            return block()
        } finally {
            leave()
        }
    }

    /** Something arrived, or is about to be tried: stay awake a moment for it. Lets go by itself. */
    fun touch(holdMs: Long = TOUCH_MS) = arriving.acquire(holdMs)

    @Synchronized
    private fun enter() {
        holders++
        if (holders == 1) working.acquire(MAX_HOLD_MS)
    }

    @Synchronized
    private fun leave() {
        holders--
        if (holders == 0) working.release()
    }

    companion object {
        /** No piece of work here takes this long; a lock forgotten by a bug still ends. */
        const val MAX_HOLD_MS = 30_000L
        const val TOUCH_MS = 5_000L
    }
}
