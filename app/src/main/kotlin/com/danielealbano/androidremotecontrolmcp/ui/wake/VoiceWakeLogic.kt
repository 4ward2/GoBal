package com.danielealbano.androidremotecontrolmcp.ui.wake

import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 长按电源唤醒页的纯逻辑（几何 + 音量包络 + 文案）。绘制在 [VoiceWakeScreen]。
 *
 * 形态是 Siri 那种「屏幕边缘的虹彩辉光」：光斑圆心压在屏幕边线上、半径小于屏幕，
 * 辉光只从四周往里渗，**中心始终透明**——这样底下的界面看得见，叠加层才像一层光
 * 而不是一块遮板。这也是它和旧版最根本的差别：旧版画的是一圈锯齿状实心带
 * （直线连点的多边形填充）外加 canvas-nest 粒子网，把屏幕中间糊住了。
 */
object VoiceWakeLogic {
    enum class Phase {
        NeedMic,
        Listening,
        Sending,
        Done,
        Error,
    }

    /**
     * 边缘光斑数量。
     *
     * 1440×3120 的周长是 9120px，6 个光斑（单个有效铺展 ~1000px）盖不满，
     * 实测会出现整条边没光的空档。10 个刚好连续又不至于糊成一圈均匀亮带。
     */
    const val BLOB_COUNT = 10

    data class Blob(
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val alpha: Float,
        val colorIndex: Int,
    )

    // ── 音量 ──────────────────────────────────────────────────────────────

    /**
     * 麦克风缓冲的平均绝对振幅 → 0..1。
     *
     * 走平方根而不是线性：说话的 avg 多在 400~3000，线性映射下正常音量只推动辉光
     * 十几个百分点，看不出反应。平方根把低段拉开，轻声说话也有可见起伏。
     */
    fun normalizeLevel(avgAbs: Int): Float {
        if (avgAbs <= NOISE_FLOOR) return 0f
        val span = (avgAbs - NOISE_FLOOR).toFloat() / (LOUD_ABS - NOISE_FLOOR).toFloat()
        return sqrt(span.coerceIn(0f, 1f))
    }

    /**
     * 快起慢落的指数平滑。
     *
     * 录音缓冲约 200ms，音量包络只有 ~5Hz，直接驱动 60fps 的辉光会一跳一跳。
     * 起音快（跟得上开口），回落慢（字与字之间的停顿不会让光灭掉）。
     */
    fun smoothLevel(
        previous: Float,
        target: Float,
        dtSec: Float,
    ): Float {
        if (dtSec <= 0f) return previous
        val tau = if (target > previous) ATTACK_SEC else RELEASE_SEC
        val k = (1f - exp(-dtSec / tau)).coerceIn(0f, 1f)
        return previous + (target - previous) * k
    }

    // ── 强度 ──────────────────────────────────────────────────────────────

    /**
     * 不说话时的呼吸底噪。各相位节奏不同，不看文字也能分辨状态：
     * 待听是缓慢的呼吸，思考（Sending）是更快更亮的脉动，出错是中速警示。
     */
    fun baseIntensity(
        phase: Phase,
        timeSec: Float,
    ): Float =
        when (phase) {
            Phase.NeedMic -> 0.20f
            Phase.Listening -> 0.32f + 0.10f * sin(timeSec * 1.5f)
            Phase.Sending -> 0.60f + 0.16f * sin(timeSec * 4.2f)
            Phase.Done -> 0.82f
            Phase.Error -> 0.44f + 0.12f * sin(timeSec * 2.6f)
        }

    /** 底噪 + 人声推动。只有真在听的时候才让音量参与，否则播报/报错时会乱跳。 */
    fun glowIntensity(
        phase: Phase,
        level: Float,
        timeSec: Float,
    ): Float {
        val voice = if (phase == Phase.Listening) level.coerceIn(0f, 1f) * VOICE_GAIN else 0f
        return (baseIntensity(phase, timeSec) + voice).coerceIn(0f, 1f)
    }

    // ── 几何 ──────────────────────────────────────────────────────────────

    /** s 沿屏幕周长走一圈（取小数部分），返回边线上的点。 */
    fun pointOnPerimeter(
        width: Float,
        height: Float,
        s: Float,
    ): Pair<Float, Float> {
        val w = width.coerceAtLeast(1f)
        val h = height.coerceAtLeast(1f)
        val wrapped = ((s % 1f) + 1f) % 1f
        var d = wrapped * 2f * (w + h)
        if (d < w) return d to 0f
        d -= w
        if (d < h) return w to d
        d -= h
        if (d < w) return (w - d) to h
        d -= w
        return 0f to (h - d)
    }

    /**
     * 沿屏幕四周游走的光斑。
     *
     * 每个光斑速度略有差异，于是它们会缓慢相对漂移、周期性地在某条边上重叠 ——
     * 重叠处（加色混合）会浮出新的色相，这正是 Siri 那种虹彩流动感的来源，
     * 不需要真的去做色相循环。
     */
    fun rimBlobs(
        width: Float,
        height: Float,
        timeSec: Float,
        intensity: Float,
        count: Int = BLOB_COUNT,
    ): List<Blob> {
        if (width < 8f || height < 8f || count <= 0) return emptyList()
        val minDim = min(width, height)
        val clamped = intensity.coerceIn(0f, 1f)
        return List(count) { i ->
            // 速度按 i 轮转取三档，保证不是所有光斑同步转圈
            val speed = BASE_SPEED + SPEED_SPREAD * ((i % 3) - 1)
            val s = i.toFloat() / count + timeSec * speed
            val (x, y) = pointOnPerimeter(width, height, s)
            val wobble = 0.5f + 0.5f * sin(timeSec * 1.3f + i * 1.7f)
            val radius = minDim * (0.34f + 0.18f * wobble) * (0.74f + 0.38f * clamped)
            val alpha = (0.20f + 0.32f * clamped) * (0.72f + 0.28f * wobble)
            Blob(
                cx = x,
                cy = y,
                radius = radius,
                alpha = alpha.coerceIn(0f, MAX_BLOB_ALPHA),
                colorIndex = i,
            )
        }
    }

    /** 贴着屏幕内缘那道细光线的描边宽度（px）。 */
    fun rimStrokeWidth(
        minDim: Float,
        intensity: Float,
    ): Float = minDim * (0.0035f + 0.0090f * intensity.coerceIn(0f, 1f))

    // ── 文案 ──────────────────────────────────────────────────────────────

    fun headline(
        phase: Phase,
        transcript: String = "",
        error: String? = null,
    ): String =
        when (phase) {
            Phase.NeedMic -> "需要麦克风权限"
            Phase.Listening -> "请开始语音输入"
            Phase.Sending -> transcript.ifBlank { "正在发送…" }
            Phase.Done -> "已收到"
            Phase.Error -> error?.ifBlank { "没听清，请再试一次" } ?: "没听清，请再试一次"
        }

    /**
     * @param dismissible 宿主是否接触摸。穿透式悬浮窗下点屏幕会落到下层应用，
     *   再说「点屏幕关闭」就是错的指引。
     */
    fun subtitle(
        phase: Phase,
        dismissible: Boolean = true,
    ): String =
        when (phase) {
            Phase.NeedMic -> "授权后长按电源键即可语音输入"
            Phase.Listening -> if (dismissible) LISTENING_TAPPABLE else LISTENING_PASSTHROUGH
            Phase.Sending -> "正在交给机上助手"
            Phase.Done -> "可以继续操作手机"
            Phase.Error -> if (dismissible) ERROR_TAPPABLE else ERROR_PASSTHROUGH
        }

    private const val LISTENING_TAPPABLE = "说完稍停就会发送 · 点屏幕关闭"
    private const val LISTENING_PASSTHROUGH = "说完稍停就会发送 · 手机照常可用"
    private const val ERROR_TAPPABLE = "点屏幕关闭，再长按电源键重试"
    private const val ERROR_PASSTHROUGH = "稍后自动关闭，可再长按电源键重试"
    private const val NOISE_FLOOR = 90
    private const val LOUD_ABS = 3_200
    private const val ATTACK_SEC = 0.055f
    private const val RELEASE_SEC = 0.30f
    private const val VOICE_GAIN = 0.62f
    private const val BASE_SPEED = 0.030f
    private const val SPEED_SPREAD = 0.014f
    private const val MAX_BLOB_ALPHA = 0.70f
}
