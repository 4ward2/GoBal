package com.danielealbano.androidremotecontrolmcp.services.skill

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** What the picker reports back. */
class PickerCallbacks(
    val onConfirm: (List<PickBox>) -> Unit,
    val onCancel: () -> Unit,
)

/**
 * The glasses view: over a frozen screen it outlines every text OCR read (blue) and every picture
 * (purple). Tapping one selects it and shows 确认 / 多选 above it (below when there is no room).
 * 多选 keeps the pick marked (amber) and waits for the next; 确认 ends with everything marked plus
 * the current pick. An accessibility overlay of the service: nothing here needs a permission.
 */
class ScreenPicker(
    private val service: AccessibilityService,
    private val boxes: List<PickBox>,
    private val callbacks: PickerCallbacks,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private val marked = mutableListOf<PickBox>()
    private var current: PickBox? = null
    private lateinit var canvasView: PickCanvas
    private lateinit var popup: LinearLayout

    fun show() {
        canvasView = PickCanvas(service)
        popup = popupBar()
        val frame =
            FrameLayout(service).apply {
                addView(canvasView, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(topBar(), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
                addView(popup, FrameLayout.LayoutParams(WRAP, WRAP))
            }
        val lp =
            WindowManager
                .LayoutParams(
                    MATCH,
                    MATCH,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(frame, lp)
        root = frame
    }

    fun dismiss() {
        root?.let { runCatching { windowManager.removeViewImmediate(it) } }
        root = null
    }

    private fun pick(box: PickBox) {
        current = box
        placePopup(box)
        canvasView.invalidate()
    }

    private fun confirm() {
        val chosen = marked + listOfNotNull(current)
        if (chosen.isNotEmpty()) callbacks.onConfirm(chosen.distinct())
    }

    private fun multi() {
        current?.let { if (it !in marked) marked += it }
        current = null
        popup.visibility = View.GONE
        canvasView.invalidate()
    }

    /** Above the box when there is room, otherwise below it; kept inside the screen. */
    private fun placePopup(box: PickBox) {
        popup.visibility = View.VISIBLE
        popup.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val origin = IntArray(2).also { canvasView.getLocationOnScreen(it) }
        val gap = service.dpPx(POPUP_GAP_DP)
        val above = box.top - origin[1] - popup.measuredHeight - gap
        val top = if (above >= service.dpPx(TOP_BAR_DP)) above else box.bottom - origin[1] + gap
        val edge = service.dpPx(POPUP_EDGE_DP)
        val maxLeft = (canvasView.width - popup.measuredWidth - edge).coerceAtLeast(edge)
        val left = (box.centerX.toInt() - origin[0] - popup.measuredWidth / 2).coerceIn(edge, maxLeft)
        popup.layoutParams =
            FrameLayout.LayoutParams(WRAP, WRAP).apply {
                leftMargin = left
                topMargin = top
            }
    }

    private fun popupBar() =
        LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            val panel = service.roundedPanel(BAR_COLOR, RADIUS_DP)
            panel.setStroke(service.dpPx(STROKE_DP), SELECTED_COLOR)
            background = panel
            elevation = service.dpPx(POPUP_GAP_DP).toFloat()
            addView(button("确认") { confirm() })
            addView(button("多选") { multi() })
        }

    private fun topBar() =
        LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR_COLOR)
            setPadding(service.dpPx(PAD_DP), service.dpPx(TOP_INSET_DP), service.dpPx(PAD_DP), service.dpPx(PAD_DP))
            addView(
                TextView(service).apply {
                    text = "点选要识别的文字或图片；选一个会帮你点它，多选则作为核对项"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, HINT_SP)
                },
                LinearLayout.LayoutParams(0, WRAP, 1f),
            )
            addView(button("✕ 取消") { callbacks.onCancel() })
        }

    private fun button(
        label: String,
        onClick: () -> Unit,
    ) = TextView(service).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_SP)
        gravity = Gravity.CENTER
        minHeight = service.dpPx(MIN_TOUCH_DP)
        minWidth = service.dpPx(MIN_TOUCH_DP)
        setPadding(service.dpPx(PAD_DP), 0, service.dpPx(PAD_DP), 0)
        setOnClickListener { onClick() }
    }

    /** Draws the boxes over the frozen screen and turns taps into picks. */
    private inner class PickCanvas(
        context: Context,
    ) : View(context) {
        private val stroke = service.dpPx(STROKE_DP).toFloat()
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val origin = IntArray(2)

        init {
            contentDescription = "截图识别：点选文字或图片"
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(DIM_COLOR)
            getLocationOnScreen(origin)
            boxes.forEach { box ->
                val r =
                    RectF(
                        box.left - origin[0].toFloat(),
                        box.top - origin[1].toFloat(),
                        box.right - origin[0].toFloat(),
                        box.bottom - origin[1].toFloat(),
                    )
                val color =
                    when {
                        box == current -> SELECTED_COLOR
                        box in marked -> MARKED_COLOR
                        box.isImage -> IMAGE_COLOR
                        else -> TEXT_COLOR
                    }
                if (box == current || box in marked) {
                    fill.color = Color.argb(FILL_ALPHA, Color.red(color), Color.green(color), Color.blue(color))
                    canvas.drawRoundRect(r, stroke * 2, stroke * 2, fill)
                }
                outline.color = color
                outline.strokeWidth = if (box == current) stroke * 2 else stroke
                canvas.drawRoundRect(r, stroke * 2, stroke * 2, outline)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                getLocationOnScreen(origin)
                ScreenPicks.boxAt(boxes, event.x + origin[0], event.y + origin[1])?.let(::pick)
            }
            return true
        }

        override fun performClick(): Boolean = super.performClick()
    }

    private companion object {
        const val MATCH = WindowManager.LayoutParams.MATCH_PARENT
        const val WRAP = WindowManager.LayoutParams.WRAP_CONTENT
        const val MIN_TOUCH_DP = 48
        const val PAD_DP = 12
        const val TOP_INSET_DP = 36
        const val TOP_BAR_DP = 88
        const val POPUP_GAP_DP = 8
        const val RADIUS_DP = 20
        const val STROKE_DP = 2
        const val HINT_SP = 13f
        const val BUTTON_SP = 15f
        const val POPUP_EDGE_DP = 8
        const val FILL_ALPHA = 0x33
        val DIM_COLOR = Color.argb(0x33, 0, 0, 0)
        val BAR_COLOR = Color.argb(0xE6, 0x20, 0x20, 0x24)
        val TEXT_COLOR = Color.rgb(0x42, 0x85, 0xF4)
        val IMAGE_COLOR = Color.rgb(0xAB, 0x47, 0xBC)
        val SELECTED_COLOR = Color.rgb(0x00, 0xB0, 0xFF)
        val MARKED_COLOR = Color.rgb(0xFF, 0xB3, 0x00)
    }
}
