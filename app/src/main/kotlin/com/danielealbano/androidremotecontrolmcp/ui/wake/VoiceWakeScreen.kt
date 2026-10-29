@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.wake

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.theme.RecordGlowBlue
import com.danielealbano.androidremotecontrolmcp.ui.theme.RecordGlowSky
import com.danielealbano.androidremotecontrolmcp.ui.theme.RecordWaveDeep
import com.danielealbano.androidremotecontrolmcp.ui.theme.RecordWaveIndigo
import com.danielealbano.androidremotecontrolmcp.ui.theme.RecordWaveLight
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * 虹彩调色板。以 App 自身的青绿打底，向蓝 → 紫 → 品红过渡。
 *
 * 光斑用加色混合（[BlendMode.Plus]）叠加，相邻两个重叠时会自然浮出中间色相，
 * 所以不必真的做色相循环就有 Siri 那种流动的彩色感。
 */
private val GlowPalette =
    listOf(
        Color(0xFF3BE0C8),
        Color(0xFF32B7F0),
        Color(0xFF4C8DFF),
        Color(0xFF9B5CFF),
        Color(0xFFFF5CA8),
        Color(0xFF32B7F0),
    )

/** AI 录制时整圈转成蓝色水波：一眼就知道这次说的话会被做成技能。 */
private val RecordPalette =
    listOf(RecordGlowBlue, RecordWaveLight, RecordWaveDeep, RecordGlowSky, RecordWaveIndigo, RecordWaveLight)

/** 穿透模式下文案的底片色。 */
private val CaptionBackdrop = Color(0xE6000A12)

/** 出错时整体转暖红，不再用彩色（彩色会读成「一切正常」）。 */
private val ErrorPalette =
    listOf(
        Color(0xFFFF6B5C),
        Color(0xFFFF8A5C),
        Color(0xFFE8564E),
        Color(0xFFFF6B5C),
        Color(0xFFFF9A6B),
        Color(0xFFE8564E),
    )

/**
 * @param onDismiss 为 null 表示当前宿主不接触摸（穿透式悬浮窗），此时不装点击手势，
 *   文案也不会再说「点屏幕关闭」。
 * @param chromeless 去掉全屏压暗与大面积文字托底，只留辉光本身。悬浮窗模式下即便
 *   触摸能穿透，一层全屏暗角在观感上依然是「遮罩」。
 */
@Composable
fun VoiceWakeScreen(
    ui: VoiceWakeUiState,
    level: Float,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
    chromeless: Boolean = false,
) {
    var dismissArmed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(800)
        dismissArmed = true
    }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .then(
                    if (onDismiss == null) {
                        Modifier
                    } else {
                        Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = { if (dismissArmed) onDismiss() },
                        )
                    },
                ),
    ) {
        SiriGlow(phase = ui.phase, level = level, chromeless = chromeless, recording = ui.aiRecording)
        // 穿透模式下底下可能是桌面图标、列表文字这类高频内容，而全屏压暗已经去掉，
        // 所以给文案自带一块圆角底片：够不透才压得住白图标，且只有文字那么大 ——
        // 这正是「只在需要处做窄遮罩」。
        val captionSurface =
            if (chromeless) {
                Modifier
                    .background(CaptionBackdrop, RoundedCornerShape(22.dp))
                    .padding(horizontal = 22.dp, vertical = 14.dp)
            } else {
                Modifier.fillMaxWidth()
            }
        WakeCaption(
            ui = ui,
            dismissible = onDismiss != null,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = if (chromeless) 128.dp else 96.dp)
                    .then(captionSurface),
        )
    }
}

@Composable
private fun SiriGlow(
    phase: VoiceWakeLogic.Phase,
    level: Float,
    chromeless: Boolean,
    recording: Boolean,
) {
    var clock by remember { mutableFloatStateOf(0f) }
    var smoothed by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt =
                    if (last == 0L) {
                        0.016f
                    } else {
                        ((now - last) / 1_000_000_000f).coerceIn(0.004f, 0.05f)
                    }
                last = now
                clock += dt
                // 在帧循环里平滑：音量包络只有 ~5Hz，直接用会一跳一跳
                smoothed = VoiceWakeLogic.smoothLevel(smoothed, level, dt)
            }
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w < 8f || h < 8f) return@Canvas
        val minDim = min(w, h)
        val palette =
            when {
                phase == VoiceWakeLogic.Phase.Error -> ErrorPalette
                recording -> RecordPalette
                else -> GlowPalette
            }
        val intensity = VoiceWakeLogic.glowIntensity(phase, smoothed, clock)

        if (!chromeless) drawVignette(w, h)
        drawRimBlobs(w, h, clock, intensity, palette)
        drawRimLine(w, h, minDim, intensity, palette)
        // chromeless 时文案自带底片，不需要整条底部渐变
        if (!chromeless) drawCaptionScrim(w, h)
    }
}

/**
 * 极轻的四角压暗。只为让边缘辉光有个落脚的底，中心保持透明 ——
 * 叠加层不该把用户正在看的界面遮住。
 */
private fun DrawScope.drawVignette(
    w: Float,
    h: Float,
) {
    drawRect(
        brush =
            Brush.radialGradient(
                0.0f to Color.Transparent,
                0.58f to Color.Transparent,
                1.0f to Color(0x59000B14),
                center = Offset(w / 2f, h / 2f),
                radius = min(w, h) * 0.95f,
            ),
    )
}

private fun DrawScope.drawRimBlobs(
    w: Float,
    h: Float,
    timeSec: Float,
    intensity: Float,
    palette: List<Color>,
) {
    VoiceWakeLogic.rimBlobs(w, h, timeSec, intensity).forEach { blob ->
        val c = palette[blob.colorIndex % palette.size]
        val center = Offset(blob.cx, blob.cy)
        drawCircle(
            brush =
                Brush.radialGradient(
                    0.00f to c.copy(alpha = blob.alpha),
                    0.32f to c.copy(alpha = blob.alpha * 0.55f),
                    0.66f to c.copy(alpha = blob.alpha * 0.18f),
                    1.00f to Color.Transparent,
                    center = center,
                    radius = blob.radius,
                ),
            radius = blob.radius,
            center = center,
            // 加色混合：重叠处变亮并浮出新色相，虹彩感就来自这里
            blendMode = BlendMode.Plus,
        )
    }
}

/**
 * 贴着屏幕内缘的一道细光线。
 *
 * 用三层「越宽越淡」的描边堆出泛光，而不是 Modifier.blur —— 全屏实时模糊在
 * 60fps 下开销明显，这种叠描边几乎不要钱，观感上区别很小。
 */
private fun DrawScope.drawRimLine(
    w: Float,
    h: Float,
    minDim: Float,
    intensity: Float,
    palette: List<Color>,
) {
    val base = VoiceWakeLogic.rimStrokeWidth(minDim, intensity)
    // 圆角原取 minDim*0.14（1440 屏上约 202px），比机身实际转角大，描边会从转角内侧切过去
    val inset = minDim * 0.006f
    val corner = CornerRadius(minDim * 0.085f, minDim * 0.085f)
    val brush = Brush.sweepGradient(palette + palette.first(), center = Offset(w / 2f, h / 2f))
    // 越宽越淡地叠三层堆出泛光；最锐那层压到 0.34，免得它盖过光斑、变成一圈硬描边
    val layers =
        listOf(
            base * 9.0f to 0.09f,
            base * 3.4f to 0.18f,
            base * 1.0f to 0.34f,
        )
    layers.forEach { (width, alphaScale) ->
        drawRoundRect(
            brush = brush,
            topLeft = Offset(inset, inset),
            size = Size(w - 2f * inset, h - 2f * inset),
            cornerRadius = corner,
            alpha = (0.18f + 0.82f * intensity) * alphaScale,
            style = Stroke(width = width),
            blendMode = BlendMode.Plus,
        )
    }
}

/** 全屏（Activity）模式下底部文案的托底，避免字压在亮色界面上读不出来。 */
private fun DrawScope.drawCaptionScrim(
    w: Float,
    h: Float,
) {
    val top = h * 0.58f
    drawRect(
        brush =
            Brush.verticalGradient(
                0.00f to Color.Transparent,
                0.45f to Color(0x8A000A12),
                1.00f to Color(0xD9000A12),
                startY = top,
                endY = h,
            ),
        topLeft = Offset(0f, top),
        size = Size(w, h - top),
    )
}

/** The words under the glow: what to do now, and how to leave. */
@Composable
private fun WakeCaption(
    ui: VoiceWakeUiState,
    dismissible: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text =
                if (ui.phase == VoiceWakeLogic.Phase.Listening) {
                    if (ui.aiRecording) "AI 录制：说出要手机做的事" else stringResource(R.string.voice_wake_prompt)
                } else {
                    VoiceWakeLogic.headline(ui.phase, ui.transcript, ui.error)
                },
            color = Color(0xFFF2F7FA),
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = VoiceWakeLogic.subtitle(ui.phase, dismissible = dismissible),
            color = Color(0x99E7F1F6),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (ui.phase == VoiceWakeLogic.Phase.NeedMic) {
            Text(
                text = stringResource(R.string.voice_wake_mic_hint),
                color = Color(0x88E7F1F6),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
