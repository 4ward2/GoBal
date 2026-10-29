package com.danielealbano.androidremotecontrolmcp.services.assist

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("SplashSkipRules")
class SplashSkipRulesTest {
    private fun facts(
        text: String? = null,
        description: String? = null,
        viewId: String? = null,
        width: Int = 200,
        height: Int = 90,
    ) = SplashNodeFacts(
        text = text,
        contentDescription = description,
        viewIdResourceName = viewId,
        width = width,
        height = height,
        screenWidth = SCREEN_WIDTH,
        screenHeight = SCREEN_HEIGHT,
    )

    @ParameterizedTest
    @ValueSource(
        strings = ["跳过", "跳过 3", "跳过3s", "3s | 跳过", "5 跳过", "跳过广告", "Skip", "SKIP AD 5", "跳過", "3秒后跳过", "广告 3s | 跳过"],
    )
    fun `accepts short skip labels with optional countdown`(label: String) {
        assertTrue(SplashSkipRules.isSkipControl(facts(text = label)))
    }

    @Test
    fun `accepts skip label given only as content description`() {
        assertTrue(SplashSkipRules.isSkipControl(facts(description = "跳过")))
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["跳过此步骤将无法领取新人红包", "不要跳过", "立即下载", "关闭", "摇一摇 跳转详情页", "skipped items"],
    )
    fun `rejects labels that are not a bare skip control`(label: String) {
        assertFalse(SplashSkipRules.isSkipControl(facts(text = label)))
    }

    @Test
    fun `accepts ad sdk skip view id with countdown-only label`() {
        val node = facts(text = "5", viewId = "com.sankuai.meituan:id/tt_splash_skip_btn")
        assertTrue(SplashSkipRules.isSkipControl(node))
    }

    @Test
    fun `accepts ad sdk skip view id without label`() {
        assertTrue(SplashSkipRules.isSkipControl(facts(viewId = "com.foo:id/ksad_skip_view")))
    }

    @Test
    fun `rejects skip view id whose label says something else`() {
        assertFalse(SplashSkipRules.isSkipControl(facts(text = "查看详情", viewId = "com.foo:id/skip_container")))
    }

    @Test
    fun `rejects jump view id because sdks use it for the ad click-through`() {
        assertFalse(SplashSkipRules.isSkipControl(facts(viewId = "com.foo:id/splash_jump_btn")))
    }

    @Test
    fun `rejects oversized nodes even with a skip label`() {
        assertFalse(SplashSkipRules.isSkipControl(facts(text = "跳过", width = SCREEN_WIDTH, height = SCREEN_HEIGHT)))
    }

    @Test
    fun `rejects invisible zero-size nodes`() {
        assertFalse(SplashSkipRules.isSkipControl(facts(text = "跳过", width = 0, height = 0)))
    }

    companion object {
        private const val SCREEN_WIDTH = 1440
        private const val SCREEN_HEIGHT = 3120
    }
}
