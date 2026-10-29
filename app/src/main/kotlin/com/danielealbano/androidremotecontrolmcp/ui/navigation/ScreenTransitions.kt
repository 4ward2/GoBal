package com.danielealbano.androidremotecontrolmcp.ui.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Messenger-style page motion: a pushed page slides in from the right over the one below, which
 * drifts a little to the left; popping reverses it. Pure movement, no fading, so the pages never
 * show through each other mid-way. One spec for the top-level screens and the Settings graph.
 */
object ScreenTransitions {
    const val DURATION_MS = 320

    /** How far the page underneath moves while the new one covers it (a fraction of its width). */
    private const val PARALLAX = 0.3f

    // Control points of the "emphasized decelerate" curve: fast start, soft landing.
    private const val EASE_X1 = 0.05f
    private const val EASE_Y1 = 0.7f
    private const val EASE_X2 = 0.1f
    private const val EASE_Y2 = 1f
    private val ease = CubicBezierEasing(EASE_X1, EASE_Y1, EASE_X2, EASE_Y2)

    private fun <T> spec() = tween<T>(DURATION_MS, easing = ease)

    /** The new page coming in from the right. */
    val pushEnter: EnterTransition = slideInHorizontally(spec()) { it }

    /** The page being covered: slides part-way left. */
    val pushExit: ExitTransition = slideOutHorizontally(spec()) { -(it * PARALLAX).toInt() }

    /** The page underneath coming back from its parked position on the left. */
    val popEnter: EnterTransition = slideInHorizontally(spec()) { -(it * PARALLAX).toInt() }

    /** The page being dismissed: slides out to the right. */
    val popExit: ExitTransition = slideOutHorizontally(spec()) { it }

    /**
     * For `AnimatedContent`: forward when going deeper, backward when returning. The page on top is
     * always the one that moves fully: the new page when pushing, the dismissed page when popping.
     */
    fun transform(forward: Boolean): ContentTransform {
        val enter = if (forward) pushEnter else popEnter
        val exit = if (forward) pushExit else popExit
        return ContentTransform(enter, exit, targetContentZIndex = zIndexOf(forward))
    }

    /** Z-index of the incoming page: above the current one when pushing, below it when popping. */
    fun zIndexOf(forward: Boolean): Float = if (forward) ABOVE else BELOW

    private const val ABOVE = 1f
    private const val BELOW = -1f
}
