package com.danielealbano.androidremotecontrolmcp.services.skill

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("SkillParams")
class SkillParamsTest {
    private val recorded =
        Skill(
            name = "点蜜雪冰城外卖",
            steps =
                listOf(
                    SkillStep.Launch("com.sankuai.meituan"),
                    SkillStep.Tap(NodeSelector(text = "搜索")),
                    SkillStep.Type(text = "蜜雪冰城"),
                    SkillStep.Tap(NodeSelector(text = "蜜雪冰城(高新店) 4.8分")),
                    SkillStep.Tap(NodeSelector(text = "去结算")),
                ),
        )

    @Test
    fun `parameterize replaces the literal everywhere and declares the default`() {
        val skill = SkillParams.parameterize(recorded, "店名", "蜜雪冰城")
        assertEquals("点{{店名}}外卖", skill.name)
        assertEquals(listOf(SkillParam("店名", "蜜雪冰城")), skill.params)
        assertEquals("{{店名}}", (skill.steps[2] as SkillStep.Type).text)
        // The recorded card label only fits one shop: the selector keeps the name and matches by containment.
        assertEquals(
            NodeSelector(text = "{{店名}}", match = TextMatch.CONTAINS),
            (skill.steps[3] as SkillStep.Tap).target,
        )
        assertEquals(NodeSelector(text = "搜索"), (skill.steps[1] as SkillStep.Tap).target)
    }

    @Test
    fun `resolve prefers given values, falls back to defaults and reports gaps`() {
        val skill = SkillParams.parameterize(recorded, "店名", "蜜雪冰城")
        assertEquals(mapOf("店名" to "瑞幸"), SkillParams.resolve(skill, mapOf("店名" to "瑞幸")).getOrThrow())
        assertEquals(mapOf("店名" to "蜜雪冰城"), SkillParams.resolve(skill, emptyMap()).getOrThrow())
        assertTrue(SkillParams.resolve(skill, mapOf("饮品" to "x")).isFailure)
        val noDefault = skill.copy(params = listOf(SkillParam("店名", "")))
        assertTrue(
            SkillParams
                .resolve(noDefault, emptyMap())
                .exceptionOrNull()!!
                .message!!
                .contains("店名"),
        )
    }

    @Test
    fun `fill substitutes selectors, typed text, verify text and uris`() {
        val values = mapOf("店名" to "瑞幸")
        assertEquals(
            SkillStep.Tap(NodeSelector(text = "瑞幸", match = TextMatch.CONTAINS)),
            SkillParams.fill(SkillStep.Tap(NodeSelector(text = "{{店名}}", match = TextMatch.CONTAINS)), values),
        )
        assertEquals(SkillStep.Verify("瑞幸门店"), SkillParams.fill(SkillStep.Verify("{{ 店名 }}门店"), values))
        assertEquals(
            SkillStep.Launch("a.b", "imeituan://search?q=瑞幸"),
            SkillParams.fill(SkillStep.Launch("a.b", "imeituan://search?q={{店名}}"), values),
        )
    }

    @Test
    fun `withCheckpoints adds one verify after taps on parameter text`() {
        val skill = SkillParams.parameterize(recorded, "店名", "蜜雪冰城")
        val checked = SkillParams.withCheckpoints(skill)
        assertEquals(SkillStep.Verify("{{店名}}"), checked.steps[4])
        assertEquals(recorded.steps.size + 1, checked.steps.size)
        // Applying it again does not stack checkpoints.
        assertEquals(checked, SkillParams.withCheckpoints(checked))
    }

    @Test
    fun `namesUsed lists placeholders once in order`() {
        val skill =
            Skill(
                name = "{{a}} {{b}}",
                steps = listOf(SkillStep.Type(text = "{{b}}{{c}}")),
            )
        assertEquals(listOf("a", "b", "c"), SkillParams.namesUsed(skill))
    }
}
