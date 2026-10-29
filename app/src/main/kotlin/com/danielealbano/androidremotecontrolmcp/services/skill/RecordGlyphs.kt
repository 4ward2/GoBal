package com.danielealbano.androidremotecontrolmcp.services.skill

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** The recording controls' icons. Drawn in code: no text, no emoji. */
internal enum class Glyph { PLAY, PAUSE, STOP, GLASSES }

/**
 * A black-and-white, see-through icon. With [rings] it is the floating ball itself: grey rings round a
 * white core, as on the iPhone's AssistiveTouch, the icon dark on the core; without, a white icon on
 * the dark round button it sits on.
 */
internal class GlyphView(
    context: Context,
    glyph: Glyph,
    private val rings: Boolean,
) : View(context) {
    var glyph: Glyph = glyph
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val box: Float
        val ink: Int
        if (rings) {
            RING_RADII.forEachIndexed { i, r ->
                fill.color = RING_COLORS[i]
                canvas.drawCircle(cx, cy, side * r, fill)
            }
            box = side * CORE_ICON
            ink = DARK_INK
        } else {
            box = side * BUTTON_ICON
            ink = LIGHT_INK
        }
        fill.color = ink
        line.color = ink
        line.strokeWidth = box * STROKE
        draw(canvas, cx - box / 2, cy - box / 2, box)
    }

    private fun draw(
        canvas: Canvas,
        left: Float,
        top: Float,
        box: Float,
    ) {
        fun x(f: Float) = left + box * f

        fun y(f: Float) = top + box * f
        when (glyph) {
            Glyph.PLAY -> {
                path.reset()
                path.moveTo(x(PLAY_LEFT), y(PLAY_TOP))
                path.lineTo(x(PLAY_LEFT), y(1 - PLAY_TOP))
                path.lineTo(x(PLAY_TIP), y(1f / 2))
                path.close()
                // Filled only: a stroke over a see-through fill would draw its edge twice as dark.
                fill.pathEffect = CornerPathEffect(box * PLAY_ROUND)
                canvas.drawPath(path, fill)
                fill.pathEffect = null
            }

            Glyph.PAUSE -> {
                PAUSE_BARS.forEach { bar ->
                    rect.set(x(bar), y(BAR_TOP), x(bar + BAR_WIDTH), y(1 - BAR_TOP))
                    canvas.drawRoundRect(rect, box * CORNER, box * CORNER, fill)
                }
            }

            Glyph.STOP -> {
                rect.set(x(STOP_INSET), y(STOP_INSET), x(1 - STOP_INSET), y(1 - STOP_INSET))
                canvas.drawRoundRect(rect, box * CORNER, box * CORNER, fill)
            }

            Glyph.GLASSES -> {
                canvas.drawCircle(x(LENS_X), y(LENS_Y), box * LENS_R, line)
                canvas.drawCircle(x(1 - LENS_X), y(LENS_Y), box * LENS_R, line)
                canvas.drawLine(x(BRIDGE_X), y(BRIDGE_Y), x(1 - BRIDGE_X), y(BRIDGE_Y), line)
                canvas.drawLine(x(TEMPLE_IN), y(TEMPLE_LOW), x(TEMPLE_OUT), y(TEMPLE_HIGH), line)
                canvas.drawLine(x(1 - TEMPLE_IN), y(TEMPLE_LOW), x(1 - TEMPLE_OUT), y(TEMPLE_HIGH), line)
            }
        }
    }

    private companion object {
        const val WHITE = 0xFF

        /**
         * Outer ring, middle ring, core: fractions of the ball and their colours. Grey rather than white
         * rings, so the ball shows on a white page as well as on a dark one.
         */
        val RING_RADII = floatArrayOf(0.48f, 0.38f, 0.28f)
        val RING_COLORS =
            intArrayOf(
                Color.argb(0x99, 0x8E, 0x8E, 0x93),
                Color.argb(0xB3, 0xC7, 0xC7, 0xCC),
                Color.argb(0xF2, WHITE, WHITE, WHITE),
            )
        const val CORE_ICON = 0.30f
        const val BUTTON_ICON = 0.46f
        val DARK_INK = Color.argb(0xE6, 0x1C, 0x1C, 0x1E)
        val LIGHT_INK = Color.argb(0xE6, WHITE, WHITE, WHITE)

        // Shapes in a unit box.
        const val STROKE = 0.08f
        const val CORNER = 0.06f
        const val PLAY_LEFT = 0.30f
        const val PLAY_TOP = 0.18f
        const val PLAY_TIP = 0.84f
        const val PLAY_ROUND = 0.08f
        val PAUSE_BARS = floatArrayOf(0.22f, 0.58f)
        const val BAR_WIDTH = 0.20f
        const val BAR_TOP = 0.18f
        const val STOP_INSET = 0.20f
        const val LENS_X = 0.28f
        const val LENS_Y = 0.56f
        const val LENS_R = 0.17f
        const val BRIDGE_X = 0.45f
        const val BRIDGE_Y = 0.52f
        const val TEMPLE_IN = 0.11f
        const val TEMPLE_LOW = 0.52f
        const val TEMPLE_OUT = 0.04f
        const val TEMPLE_HIGH = 0.36f
    }
}
