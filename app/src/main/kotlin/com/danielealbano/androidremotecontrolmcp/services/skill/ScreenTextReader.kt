package com.danielealbano.androidremotecontrolmcp.services.skill

import android.graphics.Bitmap
import android.util.Base64
import com.danielealbano.androidremotecontrolmcp.services.memory.OcrEngine
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import javax.inject.Inject

/** Text drawn on the target display, read from a screenshot (for checkpoints accessibility cannot see). */
class ScreenTextReader
    @Inject
    constructor(
        private val screenCapture: ScreenCaptureProvider,
        private val ocr: OcrEngine,
    ) {
        /** The recognised text, or null when no screenshot or OCR is available. */
        suspend fun read(): String? = withScreenshot(OCR_MAX_WIDTH) { ocr.recognize(it) }

        /**
         * Where [text] is drawn on the target display, in screen pixels (the screenshot is taken at
         * full size so its pixels are screen pixels), or null when it cannot be read there.
         */
        suspend fun locate(text: String): OcrHit? =
            withScreenshot(null) { shot -> ocr.locate(shot)?.let { boxes -> OcrMatch.find(boxes, text) } }

        /** Where the step's picture is on the target display, found by [ImageMatcher]. */
        suspend fun findImage(step: SkillStep.Image): OcrHit? =
            withScreenshot(null) { shot ->
                val template = Base64.decode(step.gray, Base64.NO_WRAP)
                val scale = ImageMatcher.scaleFor(step.width, step.height)
                val w = ImageMatcher.scaled(shot.width, scale)
                val h = ImageMatcher.scaled(shot.height, scale)
                val small = Bitmap.createScaledBitmap(shot, w, h, true)
                val pixels = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
                if (small !== shot) small.recycle()
                ImageMatcher
                    .find(ImageMatcher.gray(pixels), w, h, template, step.templateWidth, step.templateHeight)
                    ?.let { m ->
                        val x = (m.x + step.templateWidth / 2f) / scale
                        val y = (m.y + step.templateHeight / 2f) / scale
                        OcrHit(x, y, step.label)
                    }
            }

        private suspend fun <T> withScreenshot(
            maxWidth: Int?,
            use: suspend (Bitmap) -> T?,
        ): T? {
            val shot = screenCapture.captureScreenshotBitmap(maxWidth = maxWidth).getOrNull() ?: return null
            // Screenshots are HARDWARE bitmaps; the OCR engines read pixels, so take a software copy.
            val soft = shot.copy(Bitmap.Config.ARGB_8888, false)
            shot.recycle()
            return try {
                soft?.let { use(it) }
            } finally {
                soft?.recycle()
            }
        }

        private companion object {
            const val OCR_MAX_WIDTH = 1080
        }
    }
