package com.danielealbano.androidremotecontrolmcp.services.call

import kotlin.math.abs
import kotlin.math.max

/**
 * Peak microphone level over fixed windows, for the call log: it tells a silent microphone
 * (nothing reaches the recogniser) apart from one the recogniser simply cannot make sense of.
 */
class MicLevel(
    private val windowMs: Long = WINDOW_MS,
) {
    private var windowStart = -1L
    private var peak = 0

    /** Adds one buffer; returns the window's peak (0..32767) when a window closes, else null. */
    fun add(
        pcm: ShortArray,
        count: Int,
        nowMs: Long,
    ): Int? {
        if (windowStart < 0) windowStart = nowMs
        for (i in 0 until count) peak = max(peak, abs(pcm[i].toInt()))
        if (nowMs - windowStart < windowMs) return null
        val closed = peak
        peak = 0
        windowStart = nowMs
        return closed
    }

    private companion object {
        const val WINDOW_MS = 3000L
    }
}
