package com.danielealbano.androidremotecontrolmcp.ui.wake

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * 唤醒辉光的纯逻辑测试。
 *
 * 旧版这里测的是 canvas-nest 粒子（seed/step/linkAlpha）和锯齿状边缘波
 * （perimeterWavePoints/waveAmplitude）。那套视觉已整体换成边缘辉光，
 * 对应的函数不复存在，用例随之重写。
 */
class VoiceWakeLogicTest {
    private val w = 1080f
    private val h = 2400f

    // ── 音量 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("底噪以下归零，不让静音时的辉光抖动")
    fun normalizeLevel_floorsSilence() {
        assertEquals(0f, VoiceWakeLogic.normalizeLevel(0))
        assertEquals(0f, VoiceWakeLogic.normalizeLevel(90))
    }

    @Test
    @DisplayName("normalizeLevel 单调且落在 0..1")
    fun normalizeLevel_monotonicAndBounded() {
        var previous = 0f
        for (avg in intArrayOf(120, 400, 900, 1600, 3200, 20000)) {
            val v = VoiceWakeLogic.normalizeLevel(avg)
            assertTrue(v in 0f..1f, "avg=$avg -> $v 越界")
            assertTrue(v >= previous, "avg=$avg 处不单调：$previous -> $v")
            previous = v
        }
        assertEquals(1f, VoiceWakeLogic.normalizeLevel(20000))
    }

    @Test
    @DisplayName("平常说话音量要能明显推动辉光，不能只动几个百分点")
    fun normalizeLevel_speechIsVisible() {
        // SPEECH_ABS=400 是判定「在说话」的阈值，此时若只有 ~0.1 就看不出反应
        assertTrue(
            VoiceWakeLogic.normalizeLevel(400) > 0.3f,
            "说话阈值处的 level 太低，辉光不会有可见变化",
        )
    }

    @Test
    @DisplayName("快起慢落：起音比回落快")
    fun smoothLevel_attackFasterThanRelease() {
        val dt = 1f / 60f
        val rise = VoiceWakeLogic.smoothLevel(previous = 0f, target = 1f, dtSec = dt)
        val fall = 1f - VoiceWakeLogic.smoothLevel(previous = 1f, target = 0f, dtSec = dt)
        assertTrue(rise > fall, "起音($rise)应快于回落($fall)")
    }

    @Test
    @DisplayName("平滑会收敛到目标值，不会停在半路")
    fun smoothLevel_converges() {
        var v = 0f
        repeat(120) { v = VoiceWakeLogic.smoothLevel(v, 1f, 1f / 60f) }
        assertTrue(abs(1f - v) < 0.02f, "两秒后仍未收敛：$v")
    }

    @Test
    @DisplayName("dt 为 0 时原样返回，避免除零/NaN")
    fun smoothLevel_zeroDtIsNoop() {
        assertEquals(0.4f, VoiceWakeLogic.smoothLevel(0.4f, 1f, 0f))
    }

    // ── 强度 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("任何相位与音量组合下强度都在 0..1")
    fun glowIntensity_bounded() {
        for (phase in VoiceWakeLogic.Phase.entries) {
            for (level in listOf(0f, 0.5f, 1f, 5f, -1f)) {
                var t = 0f
                while (t < 6f) {
                    val v = VoiceWakeLogic.glowIntensity(phase, level, t)
                    assertTrue(v in 0f..1f, "$phase level=$level t=$t -> $v 越界")
                    t += 0.05f
                }
            }
        }
    }

    @Test
    @DisplayName("只有 Listening 让人声参与；其他相位不该被麦克风带着跳")
    fun glowIntensity_voiceOnlyWhileListening() {
        val t = 1.23f
        val listeningQuiet = VoiceWakeLogic.glowIntensity(VoiceWakeLogic.Phase.Listening, 0f, t)
        val listeningLoud = VoiceWakeLogic.glowIntensity(VoiceWakeLogic.Phase.Listening, 1f, t)
        assertTrue(listeningLoud > listeningQuiet, "Listening 下音量未推动辉光")

        val sendingQuiet = VoiceWakeLogic.glowIntensity(VoiceWakeLogic.Phase.Sending, 0f, t)
        val sendingLoud = VoiceWakeLogic.glowIntensity(VoiceWakeLogic.Phase.Sending, 1f, t)
        assertEquals(sendingQuiet, sendingLoud)
    }

    @Test
    @DisplayName("思考中比待听更亮，状态不看文字也能分辨")
    fun baseIntensity_sendingBrighterThanListening() {
        var t = 0f
        var sendingWins = 0
        var samples = 0
        while (t < 6f) {
            if (VoiceWakeLogic.baseIntensity(VoiceWakeLogic.Phase.Sending, t) >
                VoiceWakeLogic.baseIntensity(VoiceWakeLogic.Phase.Listening, t)
            ) {
                sendingWins++
            }
            samples++
            t += 0.05f
        }
        assertEquals(samples, sendingWins, "存在 Sending 不比 Listening 亮的时刻")
    }

    // ── 几何 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("pointOnPerimeter 始终落在屏幕边线上")
    fun pointOnPerimeter_staysOnEdge() {
        var s = -1.5f
        while (s < 2.5f) {
            val (x, y) = VoiceWakeLogic.pointOnPerimeter(w, h, s)
            assertTrue(x in 0f..w && y in 0f..h, "s=$s -> ($x,$y) 出界")
            val onEdge =
                abs(x) < 0.01f || abs(x - w) < 0.01f || abs(y) < 0.01f || abs(y - h) < 0.01f
            assertTrue(onEdge, "s=$s -> ($x,$y) 不在边线上")
            s += 0.013f
        }
    }

    @Test
    @DisplayName("pointOnPerimeter 走满一圈回到起点")
    fun pointOnPerimeter_wrapsAround() {
        assertEquals(VoiceWakeLogic.pointOnPerimeter(w, h, 0f), VoiceWakeLogic.pointOnPerimeter(w, h, 1f))
        assertEquals(VoiceWakeLogic.pointOnPerimeter(w, h, 0.25f), VoiceWakeLogic.pointOnPerimeter(w, h, 1.25f))
    }

    @Test
    @DisplayName("光斑圆心贴边、半径小于屏幕 —— 中心必须留空")
    fun rimBlobs_hugEdgeAndLeaveCentreClear() {
        val blobs = VoiceWakeLogic.rimBlobs(w, h, timeSec = 2.4f, intensity = 1f)
        assertEquals(VoiceWakeLogic.BLOB_COUNT, blobs.size)
        val minDim = min(w, h)
        blobs.forEach { b ->
            val onEdge =
                abs(b.cx) < 0.01f || abs(b.cx - w) < 0.01f ||
                    abs(b.cy) < 0.01f || abs(b.cy - h) < 0.01f
            assertTrue(onEdge, "光斑圆心 (${b.cx},${b.cy}) 脱离了边线")
            assertTrue(b.radius < minDim, "半径 ${b.radius} 不小于短边 $minDim，中心会被糊住")
            assertTrue(b.alpha in 0f..0.7f, "alpha ${b.alpha} 越界")
        }
    }

    @Test
    @DisplayName("尺寸退化时返回空列表而不是崩溃")
    fun rimBlobs_degenerateSizeIsEmpty() {
        assertTrue(VoiceWakeLogic.rimBlobs(0f, 0f, 1f, 1f).isEmpty())
        assertTrue(VoiceWakeLogic.rimBlobs(w, h, 1f, 1f, count = 0).isEmpty())
    }

    @Test
    @DisplayName("说话越响，辉光越强越大")
    fun rimBlobs_growWithIntensity() {
        val quiet = VoiceWakeLogic.rimBlobs(w, h, timeSec = 1f, intensity = 0f)
        val loud = VoiceWakeLogic.rimBlobs(w, h, timeSec = 1f, intensity = 1f)
        quiet.indices.forEach { i ->
            assertTrue(loud[i].radius > quiet[i].radius, "第 $i 个光斑没有随音量变大")
            assertTrue(loud[i].alpha > quiet[i].alpha, "第 $i 个光斑没有随音量变亮")
        }
    }

    @Test
    @DisplayName("内缘光线宽度随强度增加且恒为正")
    fun rimStrokeWidth_positiveAndGrows() {
        val minDim = min(w, h)
        val quiet = VoiceWakeLogic.rimStrokeWidth(minDim, 0f)
        val loud = VoiceWakeLogic.rimStrokeWidth(minDim, 1f)
        assertTrue(quiet > 0f, "静音时也要有一条可见的边线")
        assertTrue(loud > quiet)
    }

    // ── 文案 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Listening 提示用户开口")
    fun headline_listeningAsksUserToSpeak() {
        assertEquals("请开始语音输入", VoiceWakeLogic.headline(VoiceWakeLogic.Phase.Listening))
    }

    @Test
    @DisplayName("Sending 优先回显听到的内容，空则给兜底文案")
    fun headline_sendingEchoesTranscript() {
        assertEquals("打开相机", VoiceWakeLogic.headline(VoiceWakeLogic.Phase.Sending, "打开相机"))
        assertEquals("正在发送…", VoiceWakeLogic.headline(VoiceWakeLogic.Phase.Sending, "   "))
    }

    @Test
    @DisplayName("Error 缺具体原因时也有可读文案")
    fun headline_errorAlwaysReadable() {
        assertEquals("没听清，请再试一次", VoiceWakeLogic.headline(VoiceWakeLogic.Phase.Error, error = null))
        assertEquals("没听清，请再试一次", VoiceWakeLogic.headline(VoiceWakeLogic.Phase.Error, error = " "))
        assertEquals("网络不通", VoiceWakeLogic.headline(VoiceWakeLogic.Phase.Error, error = "网络不通"))
    }

    @Test
    @DisplayName("每个相位都有副标题（两种宿主都要有）")
    fun subtitle_coversEveryPhase() {
        VoiceWakeLogic.Phase.entries.forEach {
            assertTrue(VoiceWakeLogic.subtitle(it).isNotBlank(), "$it 缺副标题")
            assertTrue(
                VoiceWakeLogic.subtitle(it, dismissible = false).isNotBlank(),
                "$it 缺穿透模式副标题",
            )
        }
    }

    @Test
    @DisplayName("穿透模式下不能再说「点屏幕关闭」——那时点屏幕会落到下层应用")
    fun subtitle_passthroughDropsTapToClose() {
        VoiceWakeLogic.Phase.entries.forEach {
            val text = VoiceWakeLogic.subtitle(it, dismissible = false)
            assertTrue(!text.contains("点屏幕关闭"), "$it 的穿透模式副标题仍在指引点屏幕关闭：$text")
        }
        // 可点按的宿主（Activity）仍应保留这条指引
        assertTrue(
            VoiceWakeLogic.subtitle(VoiceWakeLogic.Phase.Listening).contains("点屏幕关闭"),
        )
    }
}
