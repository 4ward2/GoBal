package com.danielealbano.androidremotecontrolmcp.services.skill

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** What the floating ball's buttons do. */
class RecordBallActions(
    val onTogglePause: () -> Unit,
    val onStop: () -> Unit,
    val onInspect: () -> Unit,
)

/**
 * The floating ball shown while the user records by hand, in the look of the iPhone's AssistiveTouch:
 * grey rings round a white core, showing whether recording runs (play) or is paused. Tapping it
 * only opens or closes its buttons, which are flung out of it into a ring round it: pause / resume,
 * stop, and the glasses (pick text or pictures on a screenshot). Near an edge the ball slides in so
 * the whole ring fits, and back when it closes. Drag it anywhere.
 *
 * Two accessibility overlays of the service (no overlay permission needed; their taps come from this
 * app and are never recorded): the ball, which never changes size, and the ring, added only while
 * open and never moved. One window resized and moved at once jumps: the window manager animates the
 * move on its own.
 */
class SkillRecordBall(
    private val service: AccessibilityService,
    private val actions: RecordBallActions,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var parts: Parts? = null
    private var animator: ValueAnimator? = null

    fun render(state: RecorderState) {
        when (state) {
            RecorderState.Idle -> {
                hide()
            }

            is RecorderState.Recording -> {
                val p = parts ?: attach()
                p.ball.glyph = if (state.paused) Glyph.PAUSE else Glyph.PLAY
                p.ball.contentDescription = if (state.paused) "录制已暂停" else "正在录制"
                p.toggle.glyph = if (state.paused) Glyph.PLAY else Glyph.PAUSE
                p.toggle.contentDescription = if (state.paused) "继续录制" else "暂停录制"
            }
        }
    }

    /** Hidden while a screenshot is taken, so neither the ball nor its buttons are in it. */
    fun setHidden(hidden: Boolean) {
        val v = if (hidden) View.GONE else View.VISIBLE
        parts?.let {
            it.ballLayout.visibility = v
            it.ring.visibility = v
        }
    }

    fun hide() {
        animator?.cancel()
        animator = null
        parts?.let { p ->
            if (p.ringShown) runCatching { windowManager.removeViewImmediate(p.ring) }
            runCatching { windowManager.removeViewImmediate(p.ballLayout) }
        }
        parts = null
    }

    private fun attach(): Parts {
        val ballSize = service.dpPx(BALL_DP)
        val ball = GlyphView(service, Glyph.PLAY, rings = true)
        val ballLayout = FrameLayout(service).apply { addView(ball, FrameLayout.LayoutParams(ballSize, ballSize)) }
        val toggle = roundButton(Glyph.PAUSE, "暂停录制", actions.onTogglePause)
        val buttons =
            listOf(
                toggle,
                roundButton(Glyph.STOP, "停止并保存", actions.onStop),
                roundButton(Glyph.GLASSES, "识别屏幕上的字或图", actions.onInspect),
            )
        val ring = FrameLayout(service).apply { buttons.forEach(::addView) }
        val side = 2 * (service.dpPx(ORBIT_DP) + service.dpPx(BUTTON_DP) / 2 + service.dpPx(EDGE_DP))
        val p =
            Parts(
                ballLayout,
                ball,
                ring,
                buttons,
                overlayParams(ballSize, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).apply {
                    x = service.dpPx(START_X_DP)
                    y = service.dpPx(START_Y_DP)
                },
                overlayParams(side, RING_FLAGS),
            )
        ball.setOnClickListener { if (p.open) close(animate = true) else open() }
        ball.setOnTouchListener(DragOrTap(p.ballParams))
        // A touch on the empty part of the ring, or anywhere else but the ball, closes the menu.
        ring.setOnTouchListener { _, e ->
            val onBall =
                hypot(e.x - p.ringCx, e.y - p.ringCy) <= ballSize / 2f
            val away =
                e.actionMasked == MotionEvent.ACTION_DOWN ||
                    (e.actionMasked == MotionEvent.ACTION_OUTSIDE && !onBall)
            if (away) close(animate = true)
            true
        }
        windowManager.addView(ballLayout, p.ballParams)
        return p.also { parts = it }
    }

    /** Puts the ring round the ball (sliding the ball in from an edge if needed) and flings the buttons out. */
    private fun open() {
        val p = parts
        if (p == null || p.open) return
        animator?.cancel()
        p.open = true
        val turn = ++p.turn
        val half = service.dpPx(BALL_DP) / 2f
        val lp = p.ballParams
        p.homeX = lp.x
        p.homeY = lp.y
        val screen = service.resources.displayMetrics
        val ringHalf = p.ringParams.width / 2f
        val sx = lp.x + half
        val sy = lp.y + half
        val tx = RadialMenu.fit(sx, ringHalf, screen.widthPixels)
        val ty = RadialMenu.fit(sy, ringHalf, screen.heightPixels)
        p.ringParams.x = (tx - ringHalf).roundToInt()
        p.ringParams.y = (ty - ringHalf).roundToInt()
        place(p, sx, sy) { 0f }
        if (!p.ringShown) {
            windowManager.addView(p.ring, p.ringParams)
            p.ringShown = true
        } else {
            runCatching { windowManager.updateViewLayout(p.ring, p.ringParams) }
        }
        val glide = DecelerateInterpolator()
        val fling = OvershootInterpolator(OVERSHOOT)
        animator =
            startAnimator(OPEN_MS) { f ->
                if (p.turn == turn) {
                    val g = glide.getInterpolation((f / GLIDE_PART).coerceAtMost(1f))
                    val cx = sx + (tx - sx) * g
                    val cy = sy + (ty - sy) * g
                    moveBall(p, cx, cy)
                    place(p, cx, cy) { i ->
                        fling.getInterpolation(RadialMenu.staggered(f, i, p.buttons.size, STAGGER))
                    }
                }
            }
    }

    /** Sucks the buttons back into the ball, glides the ball home and takes the ring away. */
    private fun close(animate: Boolean) {
        val p = parts
        if (p == null || !p.open) return
        p.open = false
        val turn = ++p.turn
        animator?.cancel()
        val half = service.dpPx(BALL_DP) / 2f
        val sx = p.ballParams.x + half
        val sy = p.ballParams.y + half
        val hx = p.homeX + half
        val hy = p.homeY + half
        val done = {
            if (p.turn == turn && p.ringShown) {
                runCatching { windowManager.removeViewImmediate(p.ring) }
                p.ringShown = false
            }
        }
        if (!animate) {
            moveBall(p, hx, hy)
            done()
            return
        }
        val suck = AccelerateInterpolator()
        val glide = DecelerateInterpolator()
        val last = p.buttons.lastIndex
        animator =
            startAnimator(CLOSE_MS) { f ->
                if (p.turn == turn) {
                    val g = glide.getInterpolation(((f - (1 - GLIDE_PART)) / GLIDE_PART).coerceIn(0f, 1f))
                    val cx = sx + (hx - sx) * g
                    val cy = sy + (hy - sy) * g
                    moveBall(p, cx, cy)
                    place(p, cx, cy) { i ->
                        1 - suck.getInterpolation(RadialMenu.staggered(f, last - i, p.buttons.size, STAGGER))
                    }
                }
            }.apply { doOnEnd { done() } }
    }

    /** Moves the ball's window so its centre is at ([cx], [cy]) on screen. */
    private fun moveBall(
        p: Parts,
        cx: Float,
        cy: Float,
    ) {
        val half = service.dpPx(BALL_DP) / 2f
        val x = (cx - half).roundToInt()
        val y = (cy - half).roundToInt()
        if (x == p.ballParams.x && y == p.ballParams.y) return
        p.ballParams.x = x
        p.ballParams.y = y
        runCatching { windowManager.updateViewLayout(p.ballLayout, p.ballParams) }
    }

    /**
     * Each button at [progress] of its fling out of the ball centred at ([cx], [cy]) on screen: 0 inside
     * it, 1 in its place on the ring, spinning and growing on the way; it shows once it clears the ball.
     */
    private fun place(
        p: Parts,
        cx: Float,
        cy: Float,
        progress: (Int) -> Float,
    ) {
        val ballHalf = service.dpPx(BALL_DP) / 2f
        val buttonHalf = service.dpPx(BUTTON_DP) / 2f
        val orbit = service.dpPx(ORBIT_DP).toFloat()
        val lx = cx - p.ringParams.x
        val ly = cy - p.ringParams.y
        p.ringCx = lx
        p.ringCy = ly
        RadialMenu.angles(p.buttons.size).forEachIndexed { i, angle ->
            val t = progress(i)
            val (dx, dy) = RadialMenu.at(angle, orbit, t, SWIRL)
            val b = p.buttons[i]
            b.translationX = lx + dx - buttonHalf
            b.translationY = ly + dy - buttonHalf
            b.rotation = SPIN * (1 - t)
            val grown = START_SCALE + (1 - START_SCALE) * t.coerceIn(0f, 1f)
            b.scaleX = grown
            b.scaleY = grown
            b.alpha = ((orbit * t - ballHalf * HIDDEN_INSIDE) / buttonHalf).coerceIn(0f, 1f)
        }
    }

    private fun overlayParams(
        side: Int,
        flags: Int,
    ) = WindowManager
        .LayoutParams(
            side,
            side,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

    /** A dark, see-through round button with a white icon; it acts, then the menu closes. */
    private fun roundButton(
        glyph: Glyph,
        label: String,
        onClick: () -> Unit,
    ) = GlyphView(service, glyph, rings = false).apply {
        contentDescription = label
        val side = service.dpPx(BUTTON_DP)
        layoutParams = FrameLayout.LayoutParams(side, side)
        background =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BUTTON_COLOR)
                setStroke(service.dpPx(1), BUTTON_EDGE)
            }
        setOnClickListener {
            close(animate = true)
            onClick()
        }
    }

    /** Drags the window by the ball; a touch that barely moves is a tap. */
    private inner class DragOrTap(
        private val lp: WindowManager.LayoutParams,
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(
            v: View,
            e: MotionEvent,
        ): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        // The menu closes at once; the drag starts from where the ball really is.
                        close(animate = false)
                        startX = lp.x
                        startY = lp.y
                        dragging = true
                    }
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        parts?.let { runCatching { windowManager.updateViewLayout(it.ballLayout, lp) } }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    // A tap is a click for accessibility too (TalkBack users open the menu the same way).
                    if (!dragging) v.performClick()
                }
            }
            return true
        }
    }

    /**
     * The two windows and what is in them. [homeX]/[homeY]: where the ball was before the menu opened;
     * [ringCx]/[ringCy]: the ball's centre in the ring window.
     */
    private class Parts(
        val ballLayout: FrameLayout,
        val ball: GlyphView,
        val ring: FrameLayout,
        val buttons: List<GlyphView>,
        val ballParams: WindowManager.LayoutParams,
        val ringParams: WindowManager.LayoutParams,
    ) {
        /** Pause / resume: the first button, at the top of the ring. */
        val toggle: GlyphView get() = buttons.first()

        var open = false
        var ringShown = false
        var homeX = 0
        var homeY = 0
        var ringCx = 0f
        var ringCy = 0f

        /** Bumped on every open and close, so the frames of an earlier animation do nothing. */
        var turn = 0
    }

    private companion object {
        const val BALL_DP = 60
        const val BUTTON_DP = 48
        const val ORBIT_DP = 70
        const val EDGE_DP = 4
        const val START_X_DP = 12
        const val START_Y_DP = 160
        const val OPEN_MS = 460L
        const val CLOSE_MS = 280L
        const val GLIDE_PART = 0.35f
        const val STAGGER = 0.12f
        const val OVERSHOOT = 1.6f
        const val SPIN = -300f
        const val START_SCALE = 0.25f
        const val HIDDEN_INSIDE = 0.8f
        const val SWIRL = PI * 0.55
        const val RING_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val BUTTON_COLOR = Color.argb(0xA6, 0x1C, 0x1C, 0x1E)
        val BUTTON_EDGE = Color.argb(0x33, 0xFF, 0xFF, 0xFF)
    }
}

/** Starts a linear 0..1 animation of [ms] that calls [frame] with the progress on every frame. */
private fun startAnimator(
    ms: Long,
    frame: (Float) -> Unit,
): ValueAnimator =
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = ms
        interpolator = LinearInterpolator()
        addUpdateListener { frame(it.animatedValue as Float) }
        start()
    }
