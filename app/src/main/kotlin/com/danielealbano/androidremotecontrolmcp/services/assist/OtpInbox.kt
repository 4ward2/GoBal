package com.danielealbano.androidremotecontrolmcp.services.assist

/** A verification code waiting to be filled. */
data class PendingOtp(
    val code: String,
    val receivedAt: Long,
    val source: String,
)

/**
 * Holds the most recent verification code until it is filled or expires.
 *
 * Both the SMS observer and the notification listener feed it, so the same SMS usually arrives
 * twice; [offer] is idempotent per code, and a code that was already filled is never re-armed —
 * this is what stops the autofiller from typing the same code into a field again and again.
 * Thread-safe: every method synchronizes on the instance.
 */
class OtpInbox(
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    private var pending: PendingOtp? = null
    private val consumedCodes = ArrayDeque<String>()

    /** Offers [code]; returns true when it became the new pending code. */
    @Synchronized
    fun offer(
        code: String,
        source: String,
        now: Long,
    ): Boolean {
        val isNew = code !in consumedCodes && pending?.code != code
        if (isNew) pending = PendingOtp(code, now, source)
        return isNew
    }

    /** The pending code at [now], or null when there is none or it expired. */
    @Synchronized
    fun peek(now: Long): PendingOtp? {
        if (pending.let { it != null && now - it.receivedAt > ttlMs }) pending = null
        return pending
    }

    /** Marks [code] as filled so it is never offered again. */
    @Synchronized
    fun consume(code: String) {
        if (pending?.code == code) pending = null
        consumedCodes.addLast(code)
        while (consumedCodes.size > MAX_REMEMBERED_CODES) consumedCodes.removeFirst()
    }

    companion object {
        /** SMS codes are typically valid for 5 minutes. */
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L
        private const val MAX_REMEMBERED_CODES = 16
    }
}
