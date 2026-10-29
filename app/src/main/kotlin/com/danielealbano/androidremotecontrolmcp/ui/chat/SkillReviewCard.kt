@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.danielealbano.androidremotecontrolmcp.services.skill.Skill
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillText
import kotlin.math.roundToInt

/**
 * A recorded skill in the chat: one small card per step. Long-press and drag a step to move it;
 * tap a step to aim the next message at it (tap again to let go). [focus] is the selected step
 * when this card is the one being reviewed ([active]).
 */
@Composable
fun SkillReviewCard(
    skill: Skill,
    speaker: Speaker,
    active: Boolean,
    focus: Int?,
    actions: SkillCardActions,
) {
    AssistantRow(speaker) {
        Surface(
            shape = RoundedCornerShape(BubbleCorner),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = MaxBubbleWidth + EXTRA_WIDTH),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("录好的操作 · ${skill.name}", style = MaterialTheme.typography.titleSmall)
                StepList(skill, active, focus, actions)
                Text(
                    "长按拖动调整顺序；点选一步可以针对它给我提修改意见",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (active) {
                    TextButton(onClick = { actions.onReview(null) }) { Text("整理完了") }
                } else {
                    TextButton(onClick = { actions.onReview(skill.name) }) { Text("继续整理") }
                }
            }
        }
    }
}

@Composable
private fun StepList(
    skill: Skill,
    active: Boolean,
    focus: Int?,
    actions: SkillCardActions,
) {
    val drag = remember(skill) { StepDrag() }
    val appName = rememberAppName()
    skill.steps.forEachIndexed { index, step ->
        val selected = active && focus == index
        val lifted = drag.dragging == index
        Surface(
            shape = RoundedCornerShape(STEP_CORNER),
            color =
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shadowElevation = if (lifted) LIFT else 0.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MIN_ROW)
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer { translationY = if (lifted) drag.offset else 0f }
                    .onSizeChanged { drag.rowHeight = it.height.coerceAtLeast(1) }
                    .clickable(role = Role.Button) { actions.onFocus(skill.name, index) }
                    .pointerInput(skill, index) {
                        drag.track(this, index, skill.steps.lastIndex) { to -> actions.onMove(skill.name, index, to) }
                    },
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val colors = MaterialTheme.colorScheme
                Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                Spacer(Modifier.width(8.dp))
                val text = SkillText.describe(step, appName)
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Default.DragIndicator,
                    contentDescription = "长按拖动",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The step being dragged, how far, and the row height that turns the distance into places. */
private class StepDrag {
    var dragging by mutableStateOf<Int?>(null)
    var offset by mutableFloatStateOf(0f)
    var rowHeight by mutableIntStateOf(1)

    /** Long-press and drag the step at [index]; on release it lands [onMove] rows away (clamped to [last]). */
    suspend fun track(
        scope: PointerInputScope,
        index: Int,
        last: Int,
        onMove: (to: Int) -> Unit,
    ) = scope.detectDragGesturesAfterLongPress(
        onDragStart = { start(index) },
        onDrag = { change, amount ->
            change.consume()
            offset += amount.y
        },
        onDragEnd = {
            val to = (index + (offset / rowHeight).roundToInt()).coerceIn(0, last)
            start(null)
            if (to != index) onMove(to)
        },
        onDragCancel = { start(null) },
    )

    private fun start(index: Int?) {
        dragging = index
        offset = 0f
    }
}

/** Looks up an app's name by package, so the card says 「设置」 rather than com.android.settings. */
@Composable
private fun rememberAppName(): (String) -> String? {
    val pm = LocalContext.current.packageManager
    return remember(pm) {
        { pkg: String -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull() }
    }
}

private val EXTRA_WIDTH = 40.dp
private val STEP_CORNER = 12.dp
private val MIN_ROW = 48.dp
private val LIFT = 6.dp
