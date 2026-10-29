package com.danielealbano.androidremotecontrolmcp.services.skill

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("Skill rules")
class SkillRulesTest {
    private fun skill(
        name: String,
        vararg params: SkillParam,
    ) = Skill(name = name, params = params.toList(), steps = listOf(SkillStep.Back))

    @Nested
    @DisplayName("SkillMatcher")
    inner class Matcher {
        private val order = skill("点{{店名}}外卖", SkillParam("店名", "蜜雪冰城"))
        private val usual = skill("老样子")

        @Test
        fun `a fixed name matches ignoring fillers, spaces and punctuation`() {
            val match = SkillMatcher.match("帮我 老样子！", listOf(order, usual))
            assertEquals("老样子", match?.skill?.name)
            assertEquals(emptyMap<String, String>(), match?.params)
        }

        @Test
        fun `a pattern reads the parameter out of the sentence`() {
            val match = SkillMatcher.match("麻烦帮我点瑞幸外卖", listOf(order, usual))
            assertEquals("点{{店名}}外卖", match?.skill?.name)
            assertEquals(mapOf("店名" to "瑞幸"), match?.params)
        }

        @Test
        fun `a fixed name beats a pattern that also fits`() {
            val fixed = skill("点蜜雪冰城外卖")
            assertEquals("点蜜雪冰城外卖", SkillMatcher.match("点蜜雪冰城外卖", listOf(order, fixed))?.skill?.name)
        }

        @Test
        fun `a sentence that only contains the name does not match`() {
            assertNull(SkillMatcher.match("老样子是什么意思", listOf(usual)))
            assertNull(SkillMatcher.match("", listOf(usual)))
        }

        @Test
        fun `parameter values keep the case the user used`() {
            val search = skill("设置里搜{{关键词}}", SkillParam("关键词", "蓝牙"))
            assertEquals(mapOf("关键词" to "WLAN"), SkillMatcher.match("设置里搜WLAN", listOf(search))?.params)
            assertEquals("老样子", SkillMatcher.match("PLEASE 老样子", listOf(skill("老样子")))?.skill?.name)
        }

        @Test
        fun `parameter names keep their case`() {
            val shop = skill("order {{Shop}} now", SkillParam("Shop", "x"))
            assertEquals(mapOf("Shop" to "KFC"), SkillMatcher.match("Order KFC now", listOf(shop))?.params)
        }
    }

    @Nested
    @DisplayName("PaymentGuard")
    inner class Payment {
        @ParameterizedTest
        @ValueSource(strings = ["提交订单", "去支付 ¥23", "立即 付款", "确认支付", "Pay now", "免密支付"])
        fun `blocks submit and payment controls`(label: String) {
            assertTrue(PaymentGuard.blocks(label))
        }

        @ParameterizedTest
        @ValueSource(strings = ["去结算", "加入购物车", "蜜雪冰城", "搜索", ""])
        fun `lets ordinary controls through`(label: String) {
            assertFalse(PaymentGuard.blocks(label))
        }
    }

    @Nested
    @DisplayName("SkillValidator")
    inner class Validator {
        @Test
        fun `a complete skill has no problems`() {
            val ok =
                Skill(
                    name = "点{{店名}}外卖",
                    params = listOf(SkillParam("店名", "蜜雪冰城")),
                    steps =
                        listOf(
                            SkillStep.Launch("com.sankuai.meituan"),
                            SkillStep.Tap(NodeSelector(text = "{{店名}}", match = TextMatch.CONTAINS)),
                        ),
                )
            assertEquals(emptyList<String>(), SkillValidator.problems(ok))
        }

        @Test
        fun `reports empty name, no steps, undeclared params, bad package and empty selectors`() {
            val bad =
                Skill(
                    name = "{{x}}",
                    steps =
                        listOf(
                            SkillStep.Launch("not a package"),
                            SkillStep.Tap(NodeSelector()),
                            SkillStep.Verify(" "),
                        ),
                )
            val problems = SkillValidator.problems(bad)
            assertTrue(problems.any { "名称" in it })
            assertTrue(problems.any { "没有声明" in it })
            assertTrue(problems.any { it.startsWith("第 1 步") })
            assertTrue(problems.any { it.startsWith("第 2 步") })
            assertTrue(problems.any { it.startsWith("第 3 步") })
            assertTrue(SkillValidator.problems(skill("x").copy(steps = emptyList())).any { "至少" in it })
        }
    }
}
