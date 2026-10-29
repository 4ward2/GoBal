package com.danielealbano.androidremotecontrolmcp.services.skill

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.random.Random

@DisplayName("Recording from the chat")
class RecordingFeaturesTest {
    @Nested
    @DisplayName("ImageMatcher")
    inner class Images {
        private val width = 120
        private val height = 200

        /** A noisy screen with a distinctive pattern pasted at (70, 130). */
        private fun screen(): ByteArray {
            val rnd = Random(7)
            val px = ByteArray(width * height) { (rnd.nextInt(40) + 100).toByte() }
            pattern().forEachIndexed { i, v -> px[(130 + i / 12) * width + 70 + i % 12] = v }
            return px
        }

        private fun pattern(): ByteArray = ByteArray(12 * 12) { i -> if ((i / 12 + i % 12) % 3 == 0) 250.toByte() else 10 }

        @Test
        fun `finds the picture where it is and ignores contrast changes`() {
            val m = checkNotNull(ImageMatcher.find(screen(), width, height, pattern(), 12, 12))
            assertEquals(70, m.x)
            assertEquals(130, m.y)
            val dimmer = ByteArray(pattern().size) { ((pattern()[it].toInt() and 0xFF) / 2 + 20).toByte() }
            assertEquals(70, ImageMatcher.find(screen(), width, height, dimmer, 12, 12)?.x)
        }

        @Test
        fun `a picture that is not there, or a flat one, is not found`() {
            val other = ByteArray(12 * 12) { i -> if (i % 2 == 0) 250.toByte() else 10 }
            assertNull(ImageMatcher.find(screen(), width, height, other, 12, 12))
            assertNull(ImageMatcher.find(screen(), width, height, ByteArray(144) { 90 }, 12, 12))
            assertNull(ImageMatcher.find(screen(), width, height, pattern(), 200, 12))
        }

        @Test
        fun `templates are at most 32 pixels and never compared above the scale cap`() {
            assertEquals(ImageMatcher.MAX_SCALE, ImageMatcher.scaleFor(60, 40))
            val scale = ImageMatcher.scaleFor(640, 320)
            assertEquals(32, ImageMatcher.scaled(640, scale))
            assertEquals(128, ImageMatcher.gray(intArrayOf(0xFF808080.toInt()))[0].toInt() and 0xFF)
        }
    }

    @Nested
    @DisplayName("ScreenPicks")
    inner class Picks {
        private val word = PickBox(100, 100, 300, 160, "1km")
        private val photo = PickBox(0, 0, 400, 400, null, "店铺头图")

        private fun image(
            box: PickBox,
            tap: Boolean,
        ) = SkillStep.Image(Base64.getEncoder().encodeToString(ByteArray(4)), 2, 2, box.width, box.height, tap, box.label)

        @Test
        fun `the smallest box under the finger wins`() {
            assertEquals(word, ScreenPicks.boxAt(listOf(photo, word), 150f, 120f))
            assertEquals(photo, ScreenPicks.boxAt(listOf(photo, word), 350f, 350f))
            assertNull(ScreenPicks.boxAt(listOf(photo, word), 500f, 500f))
        }

        @Test
        fun `one pick is tapped now and recorded as an OCR or picture tap`() {
            val text = ScreenPicks.outcome(listOf(word), ::image)
            assertEquals(listOf(SkillStep.Tap(NodeSelector("1km", TextMatch.CONTAINS), scroll = false, ocr = true)), text.steps)
            assertEquals(200f, text.tapX)
            val picture = ScreenPicks.outcome(listOf(photo), ::image)
            assertTrue((picture.steps.single() as SkillStep.Image).tap)
        }

        @Test
        fun `several picks become checkpoints and nothing is tapped`() {
            val many = ScreenPicks.outcome(listOf(word, photo), ::image)
            assertNull(many.tapX)
            assertEquals(SkillStep.Verify("1km", ocr = true), many.steps[0])
            assertFalse((many.steps[1] as SkillStep.Image).tap)
        }
    }

    @Nested
    @DisplayName("SkillChatText")
    inner class Agent {
        private val skill =
            Skill(
                name = "录制的操作 09-29 10:30",
                steps = listOf(SkillStep.Launch("com.android.settings"), SkillStep.Tap(NodeSelector("搜索设置")), SkillStep.Back),
            )

        @Test
        fun `a json block with changes is applied and taken out of the reply`() {
            val reply =
                "好的，改名并删掉了返回。\n```json\n{\"name\": \"设置里搜索\", \"steps\": [" +
                    "{\"type\":\"launch\",\"package\":\"com.android.settings\"}," +
                    "{\"type\":\"tap\",\"target\":{\"text\":\"搜索设置\"}}]}\n```"
            val edit = SkillChatText.parse(reply, skill)
            assertEquals("好的，改名并删掉了返回。", edit.text)
            assertEquals("设置里搜索", edit.skill?.name)
            assertEquals(2, edit.skill?.steps?.size)
            assertEquals(SkillSource.RECORDED, edit.skill?.source)
            val gap = SkillChatText.parse("删好了。\n\n```json\n{\"name\": \"x\"}\n```\n\n\n还要改吗？", skill)
            assertEquals("删好了。\n\n还要改吗？", gap.text)
        }

        @Test
        fun `a reply without json, or with an invalid skill, changes nothing`() {
            assertNull(SkillChatText.parse("第二步是在点搜索框。", skill).skill)
            val broken = "改好了\n```json\n{\"steps\": []}\n```"
            val edit = SkillChatText.parse(broken, skill)
            assertNull(edit.skill)
            assertTrue(edit.text.contains("```json"))
        }

        @Test
        fun `moving a step and the hidden texts`() {
            val moved = SkillChatText.move(skill, 2, 0)
            assertEquals(SkillStep.Back, moved.steps.first())
            assertEquals(skill, SkillChatText.move(skill, 5, 0))
            assertTrue(SkillChatText.review(skill, 1).contains("第 2 步：点「搜索设置」"))
            assertTrue(SkillChatText.recorded(skill).contains("共 3 步"))
            assertTrue(SkillChatText.card(skill).startsWith("【录好的操作】"))
            val launch = skill.steps.first()
            assertEquals("打开「设置」", SkillText.describe(launch) { "设置" })
            assertEquals("打开「com.android.settings」", SkillText.describe(launch))
        }
    }

    @Nested
    @DisplayName("RadialMenu")
    inner class Ring {
        @Test
        fun `buttons sit evenly round the ball, the first straight up`() {
            val angles = RadialMenu.angles(3)
            val (x, y) = RadialMenu.at(angles[0], 100f, 1f, swirl = 1.0)
            assertEquals(0f, x, 0.01f)
            assertEquals(-100f, y, 0.01f)
            assertEquals(2 * Math.PI / 3, angles[1] - angles[0], 1e-9)
        }

        @Test
        fun `a flung button starts in the ball and curves out behind its angle`() {
            val (x0, y0) = RadialMenu.at(0.0, 100f, 0f, swirl = 1.0)
            assertEquals(0f, x0, 0.001f)
            assertEquals(0f, y0, 0.001f)
            val (_, y) = RadialMenu.at(0.0, 100f, 0.5f, swirl = 1.0)
            assertTrue(y < 0f, "halfway it trails behind (above) its final angle")
        }

        @Test
        fun `the ring is moved in from an edge just enough, and buttons start one after another`() {
            assertEquals(90f, RadialMenu.fit(30f, 90f, 1440))
            assertEquals(700f, RadialMenu.fit(700f, 90f, 1440))
            assertEquals(1350f, RadialMenu.fit(1430f, 90f, 1440))
            assertEquals(0f, RadialMenu.staggered(0.1f, 1, 3, 0.12f))
            assertEquals(1f, RadialMenu.staggered(1f, 2, 3, 0.12f))
        }
    }

    @Nested
    @DisplayName("SkillRecorder pause")
    inner class Pause {
        @Test
        fun `nothing is recorded while paused, picked steps are appended`() {
            val recorder = SkillRecorder()
            recorder.start()
            recorder.onEvent(RecordedEvent.WindowShown("a.b", ignored = false))
            recorder.setPaused(true)
            assertFalse(recorder.isListening)
            recorder.onEvent(RecordedEvent.Clicked("a.b", NodeSelector("x")))
            recorder.append(listOf(SkillStep.Verify("1km", ocr = true)))
            assertEquals(RecorderState.Recording(2, 0, paused = true), recorder.state.value)
            recorder.setPaused(false)
            recorder.onEvent(RecordedEvent.Clicked("a.b", NodeSelector("y")))
            val steps = recorder.takeSteps()
            assertEquals(listOf("launch", "verify", "tap"), steps.map { it::class.simpleName!!.lowercase().replace("skillstep", "") })
            assertEquals(RecorderState.Idle, recorder.state.value)
            assertNull(recorder.draft.value)
        }
    }
}
