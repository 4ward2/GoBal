package com.danielealbano.androidremotecontrolmcp.services.skill

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds a small picture (an icon, a product photo the user picked) on a screenshot, without a model:
 * normalised cross-correlation of grey levels. Both images are shrunk by the same factor first, so
 * a search over a whole screen stays in the tens of millions of multiply-adds.
 */
object ImageMatcher {
    /** Longest side of a stored template, in pixels. */
    const val MAX_SIDE = 32

    /** Never compare at more than this share of the screen resolution (keeps the search small). */
    const val MAX_SCALE = 0.15f

    /** Correlation needed to accept a match (1.0 = identical up to brightness and contrast). */
    const val MIN_SCORE = 0.75

    private const val RED = 0.299
    private const val GREEN = 0.587
    private const val BLUE = 0.114
    private const val BYTE_MASK = 0xFF
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val MIN_VARIANCE = 1e-6

    /** The factor a region of this size is shrunk by for storing and matching. */
    fun scaleFor(
        width: Int,
        height: Int,
    ): Float = min(MAX_SIDE.toFloat() / max(1, max(width, height)), MAX_SCALE)

    fun scaled(
        size: Int,
        scale: Float,
    ): Int = max(1, (size * scale).roundToInt())

    /** ARGB pixels to grey levels 0–255. */
    fun gray(argb: IntArray): ByteArray =
        ByteArray(argb.size) { i ->
            val p = argb[i]
            val r = (p shr RED_SHIFT) and BYTE_MASK
            val g = (p shr GREEN_SHIFT) and BYTE_MASK
            val b = p and BYTE_MASK
            (r * RED + g * GREEN + b * BLUE).roundToInt().toByte()
        }

    /** Where the template matches best, top-left corner in screen pixels of the shrunk image. */
    data class Match(
        val x: Int,
        val y: Int,
        val score: Double,
    )

    fun find(
        screen: ByteArray,
        screenWidth: Int,
        screenHeight: Int,
        template: ByteArray,
        templateWidth: Int,
        templateHeight: Int,
    ): Match? {
        val n = templateWidth * templateHeight
        if (templateWidth > screenWidth || templateHeight > screenHeight || n == 0) return null
        val t = Template(template, templateWidth, templateHeight)
        if (t.norm < MIN_VARIANCE) return null
        val area = Area(screen, screenWidth, screenHeight)
        var best: Match? = null
        for (y in 0..screenHeight - templateHeight) {
            for (x in 0..screenWidth - templateWidth) {
                val score = area.score(x, y, t)
                if (score >= MIN_SCORE && (best == null || score > best.score)) best = Match(x, y, score)
            }
        }
        return best
    }

    /** The template's deviations from its mean, and their norm. */
    private class Template(
        pixels: ByteArray,
        val width: Int,
        val height: Int,
    ) {
        val dev: DoubleArray
        val norm: Double

        init {
            val values = DoubleArray(pixels.size) { (pixels[it].toInt() and BYTE_MASK).toDouble() }
            val mean = values.average()
            dev = DoubleArray(values.size) { values[it] - mean }
            norm = sqrt(dev.sumOf { it * it })
        }
    }

    /** The shrunk screenshot with its summed-area tables. */
    private class Area(
        private val pixels: ByteArray,
        private val width: Int,
        height: Int,
    ) {
        private val integral = Integral(pixels, width, height)

        fun score(
            x: Int,
            y: Int,
            t: Template,
        ): Double {
            val n = t.width * t.height
            val (sum, sumSq) = integral.window(x, y, t.width, t.height)
            val variance = sumSq - sum * sum / n
            if (variance < MIN_VARIANCE) return 0.0
            var cross = 0.0
            var k = 0
            for (dy in 0 until t.height) {
                val row = (y + dy) * width + x
                for (dx in 0 until t.width) {
                    cross += (pixels[row + dx].toInt() and BYTE_MASK) * t.dev[k++]
                }
            }
            // Σ(s - s̄)(t - t̄) = Σ s(t - t̄), since Σ(t - t̄) = 0.
            return cross / (sqrt(variance) * t.norm)
        }
    }

    /** Summed-area tables of values and squares, for O(1) window sums. */
    private class Integral(
        pixels: ByteArray,
        private val width: Int,
        height: Int,
    ) {
        private val stride = width + 1
        private val sum = DoubleArray(stride * (height + 1))
        private val sumSq = DoubleArray(stride * (height + 1))

        init {
            for (y in 0 until height) {
                var row = 0.0
                var rowSq = 0.0
                for (x in 0 until width) {
                    val v = (pixels[y * width + x].toInt() and BYTE_MASK).toDouble()
                    row += v
                    rowSq += v * v
                    sum[(y + 1) * stride + x + 1] = sum[y * stride + x + 1] + row
                    sumSq[(y + 1) * stride + x + 1] = sumSq[y * stride + x + 1] + rowSq
                }
            }
        }

        fun window(
            x: Int,
            y: Int,
            w: Int,
            h: Int,
        ): Pair<Double, Double> {
            val a = y * stride + x
            val b = y * stride + x + w
            val c = (y + h) * stride + x
            val d = (y + h) * stride + x + w
            return (sum[d] - sum[b] - sum[c] + sum[a]) to (sumSq[d] - sumSq[b] - sumSq[c] + sumSq[a])
        }
    }
}
