package com.danielealbano.androidremotecontrolmcp.services.skill

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Where the floating ball's buttons sit: evenly round it, flung out along a curve. Pure, for tests. */
internal object RadialMenu {
    /** Angles (radians, screen coordinates: y down) of [count] buttons evenly round the ball, the first straight up. */
    fun angles(count: Int): List<Double> = List(count) { i -> -PI / 2 + 2 * PI * i / count }

    /**
     * A button's centre relative to the ball's at fling progress [t]: 0 in the ball, 1 in place (past 1
     * while it overshoots). On the way it trails [swirl] radians behind its angle, so it curves out
     * like something thrown from a spinning hand rather than sliding in a straight line.
     */
    fun at(
        angle: Double,
        radius: Float,
        t: Float,
        swirl: Double,
    ): Pair<Float, Float> {
        val a = angle - swirl * (1 - t.coerceIn(0f, 1f))
        return (radius * t * cos(a)).toFloat() to (radius * t * sin(a)).toFloat()
    }

    /** The ring's centre on one axis, moved just enough for a ring of [half] size either side to fit in [size]. */
    fun fit(
        center: Float,
        half: Float,
        size: Int,
    ): Float = center.coerceIn(half, (size - half).coerceAtLeast(half))

    /** Button [index]'s own progress when the whole animation is at [f] and each starts [stagger] after the last. */
    fun staggered(
        f: Float,
        index: Int,
        count: Int,
        stagger: Float,
    ): Float = ((f - index * stagger) / (1 - (count - 1) * stagger)).coerceIn(0f, 1f)
}
