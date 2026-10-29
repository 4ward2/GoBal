package com.danielealbano.androidremotecontrolmcp.services.assistant

import com.danielealbano.androidremotecontrolmcp.services.vault.VaultCategory
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultSlot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("AssistantRouter")
class AssistantRouterTest {
    private val none = emptyList<VaultSlot>()

    private fun saved(
        text: String,
        slots: List<VaultSlot> = none,
    ): AssistantRoute.SaveToVault {
        val route = AssistantRouter.route(text, slots)
        assertTrue(route is AssistantRoute.SaveToVault, "expected vault for '$text' but got $route")
        return route as AssistantRoute.SaveToVault
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "记住我的手机号 幺三八 一二三四 五六七八",
            "帮我记一下 学号 二零二一 零一零五",
            "我的手机号是一三八一二三四五六七八",
            "手机号 一 三 八 一 二 三 四 五 六 七 八",
            "我叫张三",
            "地址是北京市海淀区中关村大街二十七号",
            "remember my phone number is nine eight four five seven seven three two one zero",
            "my roll number is two zero two one five five five",
            "मेरा फ़ोन नंबर नौ आठ चार पांच सात सात तीन दो एक शून्य है",
            "मेरा नाम राहुल शर्मा है",
        ],
    )
    fun `personal details go to the vault`(text: String) {
        saved(text)
    }

    @Test
    fun `updates are recognised and keep the entry`() {
        val phone = VaultSlot("phone", "手机号", VaultCategory.CONTACT, false, emptyList(), true)
        val d = saved("把手机号改成一三九一二三四五六七八", listOf(phone)).draft
        assertEquals("13912345678", d.value)
        assertEquals(phone, d.replaces)
        val english = "change my phone number to nine eight four five seven seven three two one zero"
        assertEquals("9845773210", saved(english).draft.value)
        val card = VaultSlot("item", "借书证号", VaultCategory.OTHER, false, emptyList(), true)
        assertEquals("item", saved("借书证号换成九九九", listOf(card)).draft.slotId)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "打开美团，帮我找附近评分高的外卖",
            "打开设置，把屏幕亮度调高一点",
        ],
    )
    fun `phone commands stay phone commands`(text: String) {
        assertEquals(AssistantRoute.PhoneControl, AssistantRouter.route(text, none))
    }

    @ParameterizedTest
    @ValueSource(strings = ["把手机音量改成最大", "帮我在美团填手机号", "手机号填到美团里"])
    fun `sentences that only mention a slot name are not taken into the vault`(text: String) {
        val route = AssistantRouter.route(text, none)
        assertTrue(route == AssistantRoute.PhoneControl || route == AssistantRoute.Chat, "$text -> $route")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "今天天气怎么样",
            "手机没电了怎么办",
            "我的手机号是多少",
            "what is my phone number?",
            "给我讲个笑话",
            "save this for later",
            "记住明天要早点起",
        ],
    )
    fun `everything else goes to the assistant`(text: String) {
        assertEquals(AssistantRoute.Chat, AssistantRouter.route(text, none))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "我的银行卡密码是一二三四五六",
            "记住支付宝密码 八八八八八八",
            "my password is one two three four",
            "验证码三八二九一",
            "我的银行卡密码是一二三四五六吗",
        ],
    )
    fun `secrets are refused on the phone and never forwarded`(text: String) {
        assertTrue(AssistantRouter.route(text, none) is AssistantRoute.VaultNotSaved, text)
    }

    @Test
    fun `a misheard number meant for the vault stays on the phone`() {
        val route = AssistantRouter.route("记住我的学号 二零二一百", none)
        assertTrue(route is AssistantRoute.VaultNotSaved, "$route")
    }

    @Test
    fun `an API key is caught before anything else, even inside a vault-looking sentence`() {
        val key = "sk-" + "d4".repeat(16)
        val route = AssistantRouter.route("记住我的 key $key", none)
        assertTrue(route is AssistantRoute.SetUpProvider)
        assertEquals(key, (route as AssistantRoute.SetUpProvider).draft.apiKey)
    }

    @Test
    fun `naming a service to add routes to set-up`() {
        val route = AssistantRouter.route("帮我接入 Kimi", none)
        assertEquals("kimi", (route as AssistantRoute.SetUpProvider).draft.presetId)
    }
}
