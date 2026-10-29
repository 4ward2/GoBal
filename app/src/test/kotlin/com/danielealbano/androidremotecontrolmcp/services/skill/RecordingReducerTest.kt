package com.danielealbano.androidremotecontrolmcp.services.skill

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Recording")
class RecordingReducerTest {
    private val mt = "com.sankuai.meituan"
    private val pay = "com.eg.android.AlipayGphone"

    @Test
    fun `an app opened from the launcher becomes a launch step, taps and typing follow`() {
        val r = RecordingReducer()
        r.apply(RecordedEvent.WindowShown("launcher", ignored = true))
        r.apply(RecordedEvent.WindowShown(mt, ignored = false))
        r.apply(RecordedEvent.Clicked(mt, NodeSelector(text = "搜索")))
        r.apply(RecordedEvent.TextChanged(mt, null, "蜜"))
        r.apply(RecordedEvent.TextChanged(mt, null, "蜜雪"))
        r.apply(RecordedEvent.TextChanged(mt, null, "蜜雪冰城"))
        assertEquals(
            listOf(
                SkillStep.Launch(mt),
                SkillStep.Tap(NodeSelector(text = "搜索", packageName = mt)),
                SkillStep.Type(null, "蜜雪冰城"),
            ),
            r.result,
        )
    }

    @Test
    fun `an app reached by a tap inside the flow is not launched again`() {
        val r = RecordingReducer()
        r.apply(RecordedEvent.WindowShown(mt, ignored = false))
        r.apply(RecordedEvent.Clicked(mt, NodeSelector(text = "去支付")))
        r.apply(RecordedEvent.WindowShown(pay, ignored = false))
        assertEquals(
            listOf(SkillStep.Launch(mt), SkillStep.Tap(NodeSelector(text = "去支付", packageName = mt))),
            r.result,
        )
    }

    @Test
    fun `going home and opening another app records a second launch`() {
        val r = RecordingReducer()
        r.apply(RecordedEvent.WindowShown(mt, ignored = false))
        r.apply(RecordedEvent.WindowShown("launcher", ignored = true))
        r.apply(RecordedEvent.WindowShown(pay, ignored = false))
        assertEquals(listOf(SkillStep.Launch(mt), SkillStep.Launch(pay)), r.result)
    }

    @Test
    fun `a screen that cannot be opened by itself gives no launch, the next app that can does`() {
        val search = "com.android.settings.intelligence"
        val settings = "com.android.settings"
        val r = RecordingReducer(launchable = { it != search })
        r.apply(RecordedEvent.WindowShown("launcher", ignored = true))
        r.apply(RecordedEvent.WindowShown(search, ignored = false))
        r.apply(RecordedEvent.Clicked(search, NodeSelector(text = "电池")))
        // Opened by that tap: part of the flow, not launched.
        r.apply(RecordedEvent.WindowShown(settings, ignored = false))
        r.apply(RecordedEvent.WindowShown("launcher", ignored = true))
        r.apply(RecordedEvent.WindowShown(pay, ignored = false))
        r.apply(RecordedEvent.WindowShown(settings, ignored = false))
        assertEquals(
            listOf(SkillStep.Tap(NodeSelector(text = "电池", packageName = search)), SkillStep.Launch(pay)),
            r.result,
        )
    }

    @Test
    fun `taps without anything to recognise them by are counted, not recorded`() {
        val r = RecordingReducer()
        r.apply(RecordedEvent.Clicked(mt, null))
        r.apply(RecordedEvent.Clicked(mt, NodeSelector()))
        assertEquals(2, r.skipped)
        assertEquals(listOf(SkillStep.Launch(mt)), r.result)
    }

    @Test
    fun `typing into two fields gives two type steps`() {
        val r = RecordingReducer()
        val a = NodeSelector(resourceId = "$mt:id/a")
        val b = NodeSelector(resourceId = "$mt:id/b")
        r.apply(RecordedEvent.TextChanged(mt, a, "1"))
        r.apply(RecordedEvent.TextChanged(mt, b, "2"))
        r.apply(RecordedEvent.TextChanged(mt, b, "23"))
        assertEquals(
            listOf(
                SkillStep.Launch(mt),
                SkillStep.Type(a.copy(packageName = mt), "1"),
                SkillStep.Type(b.copy(packageName = mt), "23"),
            ),
            r.result,
        )
    }

    @Test
    fun `the recorder keeps a draft only when something was recorded`() {
        val recorder = SkillRecorder()
        recorder.start()
        assertTrue(recorder.isRecording)
        assertFalse(recorder.finish())
        assertNull(recorder.draft.value)

        recorder.start()
        recorder.onEvent(RecordedEvent.WindowShown(mt, ignored = false))
        assertEquals(RecorderState.Recording(1, 0), recorder.state.value)
        assertTrue(recorder.finish())
        assertEquals(listOf(SkillStep.Launch(mt)), recorder.draft.value?.steps)
        assertEquals(RecorderState.Idle, recorder.state.value)

        recorder.onEvent(RecordedEvent.WindowShown(pay, ignored = false))
        assertEquals(RecorderState.Idle, recorder.state.value)
    }

    @Test
    fun `long labels keep their first part and match by containment`() {
        assertEquals("去结算" to TextMatch.EXACT, SkillEventCapture.stableLabel(" 去结算 "))
        assertEquals(
            "蜜雪冰城(高新店)" to TextMatch.CONTAINS,
            SkillEventCapture.stableLabel("蜜雪冰城(高新店) 4.8分 月售9999 距离1.2km 起送¥15"),
        )
    }
}
