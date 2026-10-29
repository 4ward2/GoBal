package com.danielealbano.androidremotecontrolmcp.services.skill

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo

/** The pictures the glasses view offers besides text, and a picked picture as a replay template. */
internal object ScreenPictures {
    /** Picture regions from the accessibility tree: image views of a useful size. */
    fun find(service: AccessibilityService): List<PickBox> {
        val out = mutableListOf<PickBox>()
        val screen = service.resources.displayMetrics
        val maxArea = screen.widthPixels.toLong() * screen.heightPixels / 2
        service.windows
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() != service.packageName }
            .forEach { root -> collectPictures(root, maxArea, out) }
        return out.take(MAX_PICTURES)
    }

    private fun collectPictures(
        node: AccessibilityNodeInfo,
        maxArea: Long,
        out: MutableList<PickBox>,
    ) {
        val r = Rect().also(node::getBoundsInScreen)
        val isPicture = node.className?.toString()?.contains("Image") == true
        val big = r.width() >= MIN_PICTURE_PX && r.height() >= MIN_PICTURE_PX
        val sized = big && r.width().toLong() * r.height() <= maxArea
        if (node.isVisibleToUser && isPicture && sized) {
            out += PickBox(r.left, r.top, r.right, r.bottom, null, node.contentDescription?.toString().orEmpty())
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectPictures(it, maxArea, out) }
    }

    /** A picked picture as a small grey template, at the scale [ImageMatcher] compares at. */
    fun template(
        shot: Bitmap,
        box: PickBox,
        tap: Boolean,
    ): SkillStep.Image {
        val left = box.left.coerceIn(0, shot.width - 1)
        val top = box.top.coerceIn(0, shot.height - 1)
        val w = box.width.coerceIn(1, shot.width - left)
        val h = box.height.coerceIn(1, shot.height - top)
        val scale = ImageMatcher.scaleFor(w, h)
        val tw = ImageMatcher.scaled(w, scale)
        val th = ImageMatcher.scaled(h, scale)
        val crop = Bitmap.createBitmap(shot, left, top, w, h)
        val small = Bitmap.createScaledBitmap(crop, tw, th, true)
        val pixels = IntArray(tw * th).also { small.getPixels(it, 0, tw, 0, 0, tw, th) }
        if (small !== crop) small.recycle()
        crop.recycle()
        val gray = Base64.encodeToString(ImageMatcher.gray(pixels), Base64.NO_WRAP)
        return SkillStep.Image(gray, tw, th, w, h, tap = tap, label = box.label)
    }

    private const val MIN_PICTURE_PX = 48
    private const val MAX_PICTURES = 80
}
