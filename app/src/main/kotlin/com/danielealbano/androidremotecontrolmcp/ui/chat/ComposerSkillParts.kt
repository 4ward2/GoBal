@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.services.skill.RecordMode
import com.danielealbano.androidremotecontrolmcp.ui.theme.RecordGlowBlue
import com.danielealbano.androidremotecontrolmcp.ui.theme.RecordGlowSky
import com.danielealbano.androidremotecontrolmcp.ui.theme.RecordGlowViolet
import kotlin.math.cos
import kotlin.math.sin

/** Blues of the recording glow, running round and closing the loop. */
private val GlowColors = listOf(RecordGlowBlue, RecordGlowSky, RecordGlowViolet, RecordGlowBlue)

/**
 * A blue light running round the composer while a recording is on, with a slow breathing halo:
 * the whole chat shows at a glance that what happens now is being recorded.
 */
fun Modifier.recordingGlow(active: Boolean): Modifier =
    if (!active) {
        this
    } else {
        composed {
            val transition = rememberInfiniteTransition(label = "recording-glow")
            val turn by transition.animateFloat(
                initialValue = 0f,
                targetValue = FULL_TURN,
                animationSpec = infiniteRepeatable(tween(TURN_MS, easing = LinearEasing)),
                label = "turn",
            )
            val breath by transition.animateFloat(
                initialValue = HALO_MIN,
                targetValue = HALO_MAX,
                animationSpec = infiniteRepeatable(tween(BREATH_MS), RepeatMode.Reverse),
                label = "breath",
            )
            drawBehind {
                val angle = Math.toRadians(turn.toDouble())
                val c = Offset(size.width / 2, size.height / 2)
                val reach = size.width / 2
                val from = Offset(c.x + reach * cos(angle).toFloat(), c.y + reach * sin(angle).toFloat())
                val opposite = Offset(2 * c.x - from.x, 2 * c.y - from.y)
                val brush = Brush.linearGradient(GlowColors, start = from, end = opposite)
                val radius = CornerRadius(size.height / 2, size.height / 2)
                val halo = HALO_DP.dp.toPx()
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(-halo / 2, -halo / 2),
                    size = Size(size.width + halo, size.height + halo),
                    cornerRadius = radius,
                    alpha = breath,
                    style = Stroke(width = halo),
                )
                drawRoundRect(brush = brush, cornerRadius = radius, style = Stroke(width = LINE_DP.dp.toPx()))
            }
        }
    }

/** Above the text box: what is being recorded or replayed, and the step the next message is about. */
@Composable
fun SkillStrip(
    state: ComposerState,
    attach: AttachActions,
) {
    val recordingText =
        when (state.recording) {
            RecordMode.AI -> "AI 录制中：对着手机说出你要做的事"
            RecordMode.MANUAL -> "人工录制中：用悬浮球暂停、停止或识别屏幕"
            RecordMode.NONE -> null
        }
    recordingText?.let { text ->
        Row(modifier = Modifier.padding(StripPadding), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(DOT).background(GlowColors.first(), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = attach.skill.onStopRecording) { Text("停止录制") }
        }
    }
    state.playing?.let { name ->
        Row(modifier = Modifier.padding(StripPadding), verticalAlignment = Alignment.CenterVertically) {
            Text("正在播放「$name」…", style = MaterialTheme.typography.bodyMedium)
        }
    }
    state.focusLabel?.let { label ->
        Row(modifier = Modifier.padding(StripPadding), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = attach.skill.onClearFocus) {
                Icon(Icons.Default.Close, contentDescription = "不针对这一步了")
            }
        }
    }
}

private val StripPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 8.dp)
private val DOT = 10.dp
private const val FULL_TURN = 360f
private const val TURN_MS = 2_400
private const val BREATH_MS = 1_200
private const val HALO_MIN = 0.25f
private const val HALO_MAX = 0.7f
private const val HALO_DP = 8
private const val LINE_DP = 2
