@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import com.danielealbano.androidremotecontrolmcp.R

/** Which of several wordings of the same hint to show in a box of a given width. Pure. */
object HintFit {
    /**
     * The index of the first wording (they are ordered longest first) that fits [available] on
     * one line; the last one when none does.
     */
    fun pick(
        widths: List<Int>,
        available: Int,
    ): Int {
        val fitting = widths.indexOfFirst { it <= available }
        return if (fitting >= 0) fitting else widths.lastIndex
    }
}

/** The wordings of the composer's hint, longest first. */
internal fun composerHints(taskRunning: Boolean): List<Int> =
    if (taskRunning) {
        listOf(R.string.chat_composer_hint_task, R.string.chat_composer_hint_task_short)
    } else {
        listOf(R.string.chat_composer_hint, R.string.chat_composer_hint_short, R.string.chat_composer_hint_shortest)
    }

/**
 * The hint of an empty text box, always on one line: on a narrow or large-print phone the full
 * sentence would wrap and make the box two lines tall, so a shorter wording takes its place.
 */
@Composable
internal fun FittingHint(
    wordings: List<Int>,
    style: TextStyle,
) {
    val texts = wordings.map { stringResource(it) }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val available = constraints.maxWidth
        val chosen =
            remember(texts, style, available) {
                val widths = texts.map { measurer.measure(it, style, maxLines = 1).size.width }
                texts[HintFit.pick(widths, available)]
            }
        Text(
            text = chosen,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
