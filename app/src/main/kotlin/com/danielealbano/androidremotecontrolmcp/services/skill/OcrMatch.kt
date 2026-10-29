package com.danielealbano.androidremotecontrolmcp.services.skill

import com.danielealbano.androidremotecontrolmcp.services.memory.OcrBox
import kotlin.math.abs

/** Where a text was read on screen: its centre in image pixels, and what OCR read there. */
data class OcrHit(
    val x: Float,
    val y: Float,
    val read: String,
)

/**
 * Finds a text among OCR boxes. RapidOCR gives one box per text run ("1km"); Tesseract gives one
 * box per word, and a Chinese word is a single character, so runs are rebuilt from boxes on the same line.
 */
object OcrMatch {
    fun find(
        boxes: List<OcrBox>,
        text: String,
    ): OcrHit? {
        val wanted = SelectorMatcher.squeeze(text)
        if (wanted.isEmpty()) return null
        return inOneBox(boxes, wanted) ?: acrossBoxes(boxes, wanted)
    }

    /** The shortest box containing the text; the centre is placed proportionally inside it. */
    private fun inOneBox(
        boxes: List<OcrBox>,
        wanted: String,
    ): OcrHit? {
        val box =
            boxes
                .filter { SelectorMatcher.squeeze(it.text).contains(wanted) }
                .minByOrNull { SelectorMatcher.squeeze(it.text).length }
                ?: return null
        val read = SelectorMatcher.squeeze(box.text)
        val start = read.indexOf(wanted)
        val middle = (start + wanted.length / 2f) / read.length
        return OcrHit(box.left + box.width * middle, box.top + box.height / 2f, box.text)
    }

    /** Joins boxes line by line (left to right) and looks for the text across neighbouring boxes. */
    private fun acrossBoxes(
        boxes: List<OcrBox>,
        wanted: String,
    ): OcrHit? {
        lines(boxes).forEach { line ->
            val pieces = line.map { SelectorMatcher.squeeze(it.text) }
            val joined = pieces.joinToString("")
            val start = joined.indexOf(wanted)
            if (start >= 0) {
                val end = start + wanted.length
                var offset = 0
                val covered =
                    line.filterIndexed { i, _ ->
                        val from = offset
                        offset += pieces[i].length
                        from < end && offset > start
                    }
                val left = covered.minOf { it.left }
                val right = covered.maxOf { it.left + it.width }
                val top = covered.minOf { it.top }
                val bottom = covered.maxOf { it.top + it.height }
                return OcrHit((left + right) / 2f, (top + bottom) / 2f, covered.joinToString("") { it.text })
            }
        }
        return null
    }

    private fun lines(boxes: List<OcrBox>): List<List<OcrBox>> {
        val lines = mutableListOf<MutableList<OcrBox>>()
        boxes.sortedBy { it.top + it.height / 2 }.forEach { box ->
            val centre = box.top + box.height / 2
            val line = lines.lastOrNull()
            val first = line?.first()
            val gap = first?.let { abs(it.top + it.height / 2 - centre) }
            val sameLine = first != null && gap != null && gap < maxOf(first.height, box.height) / 2
            if (line != null && sameLine) {
                line.add(box)
            } else {
                lines += mutableListOf(box)
            }
        }
        return lines.map { line -> line.sortedBy { it.left } }
    }
}
