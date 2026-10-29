package com.danielealbano.androidremotecontrolmcp.services.skill

/** Something the user can pick on a screenshot: a text OCR read, or a picture region. */
data class PickBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** What OCR read; null for a picture. */
    val text: String?,
    /** A picture's accessibility description, if it has one. */
    val label: String = "",
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val isImage: Boolean get() = text == null

    fun contains(
        x: Float,
        y: Float,
    ): Boolean = x >= left && x < right && y >= top && y < bottom
}

/** What the pick turned into: steps for the recording, and where to tap now (single pick only). */
data class PickOutcome(
    val steps: List<SkillStep>,
    val tapX: Float?,
    val tapY: Float?,
)

/**
 * The rules of picking on a screenshot, apart from drawing it.
 *
 * One pick, confirmed: the user wants that thing tapped — it is tapped now and recorded as a tap
 * found by OCR (text) or by template matching (picture). Several picks ("多选", then 确认 on the
 * last): the user marks what the screen must show — recorded as checkpoints, nothing is tapped.
 */
object ScreenPicks {
    /** The smallest box under the finger, so a word inside a picture wins over the picture. */
    fun boxAt(
        boxes: List<PickBox>,
        x: Float,
        y: Float,
    ): PickBox? = boxes.filter { it.contains(x, y) }.minByOrNull { it.width.toLong() * it.height }

    fun outcome(
        picked: List<PickBox>,
        image: (PickBox, Boolean) -> SkillStep.Image,
    ): PickOutcome {
        if (picked.size == 1) {
            val box = picked.single()
            val step =
                box.text?.let { text ->
                    val target = NodeSelector(text = text.trim(), match = TextMatch.CONTAINS)
                    SkillStep.Tap(target, scroll = false, ocr = true)
                } ?: image(box, true)
            return PickOutcome(listOf(step), box.centerX, box.centerY)
        }
        val checks =
            picked.map { box ->
                box.text?.let { SkillStep.Verify(it.trim(), ocr = true) } ?: image(box, false)
            }
        return PickOutcome(checks, null, null)
    }
}
