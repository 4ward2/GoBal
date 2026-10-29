package com.danielealbano.androidremotecontrolmcp.services.skill

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.danielealbano.androidremotecontrolmcp.services.memory.OcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The on-screen side of a manual recording: the floating ball, and the glasses view that picks
 * texts and pictures on a screenshot. Lives in the accessibility service, which owns the overlays
 * and can take screenshots and tap.
 */
class SkillRecordingUi(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val manual: ManualRecording,
    private val ocr: OcrEngine,
    private val screenshot: suspend () -> Bitmap?,
) {
    private val ball =
        SkillRecordBall(
            service,
            RecordBallActions(onTogglePause = manual::togglePause, onStop = ::stop, onInspect = ::inspect),
        )
    private var picker: ScreenPicker? = null

    fun render(state: RecorderState) = ball.render(state)

    fun dispose() {
        ball.hide()
        picker?.dismiss()
        picker = null
    }

    private fun stop() {
        scope.launch {
            if (!manual.stop()) toast("没有录到能重放的操作")
        }
    }

    private fun inspect() {
        if (picker != null) return
        scope.launch {
            val wasPaused = manual.isPaused
            manual.setPaused(true)
            ball.setHidden(true)
            delay(SETTLE_MS)
            val shot = softCopy(screenshot())
            if (shot == null) {
                restore(wasPaused)
                toast("截不了屏")
                return@launch
            }
            val texts = withContext(Dispatchers.IO) { ocr.locate(shot) }.orEmpty()
            val words = texts.map { PickBox(it.left, it.top, it.left + it.width, it.top + it.height, it.text) }
            val boxes = words + ScreenPictures.find(service)
            if (boxes.isEmpty()) {
                shot.recycle()
                restore(wasPaused)
                toast("这一屏没认出文字或图片")
                return@launch
            }
            val callbacks =
                PickerCallbacks(
                    onConfirm = { picked -> finish(picked, shot) },
                    onCancel = {
                        closePicker(shot)
                        restore(wasPaused)
                    },
                )
            picker = ScreenPicker(service, boxes, callbacks).also { it.show() }
        }
    }

    /** Records the picks, taps a single one, then records the user's next action (even if paused before). */
    private fun finish(
        picked: List<PickBox>,
        shot: Bitmap,
    ) {
        val outcome = ScreenPicks.outcome(picked) { box, tap -> ScreenPictures.template(shot, box, tap) }
        closePicker(shot)
        manual.append(outcome.steps)
        scope.launch {
            val x = outcome.tapX
            val y = outcome.tapY
            if (x != null && y != null) {
                // Until the picker's window is really gone, input still routes to it and the tap is lost.
                delay(CLOSE_MS)
                tap(x, y)
                // The app reports our own tap as a click; it is already recorded, so skip it.
                delay(OWN_TAP_MS)
            }
            restore(wasPaused = false)
        }
    }

    private fun closePicker(shot: Bitmap) {
        picker?.dismiss()
        picker = null
        shot.recycle()
    }

    private fun restore(wasPaused: Boolean) {
        ball.setHidden(false)
        if (!wasPaused) manual.setPaused(false)
    }

    private fun tap(
        x: Float,
        y: Float,
    ) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }

    private fun softCopy(shot: Bitmap?): Bitmap? {
        val soft = shot?.copy(Bitmap.Config.ARGB_8888, false)
        shot?.recycle()
        return soft
    }

    private fun toast(text: String) {
        Toast.makeText(service, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val SETTLE_MS = 250L
        const val CLOSE_MS = 400L
        const val OWN_TAP_MS = 1_200L
        const val TAP_MS = 60L
    }
}
