package com.danielealbano.androidremotecontrolmcp.services.assist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OtpFieldRules")
class OtpFieldRulesTest {
    /** An empty, editable, unlabelled text field; each test changes only what it is about. */
    private val empty =
        OtpFieldFacts(
            isEditable = true,
            isPassword = false,
            isShowingHint = false,
            text = null,
            hint = null,
            contentDescription = null,
            viewIdResourceName = null,
            isNumberInput = false,
            maxTextLength = -1,
        )

    private fun modeFor(
        field: OtpFieldFacts,
        screenText: String = "",
    ) = OtpFieldRules.fillMode(field, CODE, screenText)

    @Test
    fun `fills a field whose hint asks for the sms code`() {
        assertEquals(OtpFillMode.WHOLE_CODE, modeFor(empty.copy(hint = "请输入短信验证码")))
    }

    @Test
    fun `fills a field whose view id names the code`() {
        val field = empty.copy(viewIdResourceName = "com.taobao.taobao:id/sms_code_input")
        assertEquals(OtpFillMode.WHOLE_CODE, modeFor(field))
    }

    @Test
    fun `fills an unlabelled numeric box on a code screen`() {
        val screen = "输入验证码\n验证码已发送至 138****8000\n59s后重新获取"
        assertEquals(OtpFillMode.WHOLE_CODE, modeFor(empty.copy(isNumberInput = true, maxTextLength = 6), screen))
    }

    @Test
    fun `does not fill an unlabelled numeric box on an unrelated screen`() {
        assertEquals(OtpFillMode.NONE, modeFor(empty.copy(isNumberInput = true), "购物车\n数量"))
    }

    @Test
    fun `never fills image captcha fields`() {
        assertEquals(OtpFillMode.NONE, modeFor(empty.copy(hint = "请输入图形验证码"), "验证码"))
    }

    @Test
    fun `never fills password fields`() {
        assertEquals(OtpFillMode.NONE, modeFor(empty.copy(hint = "验证码", isPassword = true)))
    }

    @Test
    fun `never overwrites what the user already typed`() {
        assertEquals(OtpFillMode.NONE, modeFor(empty.copy(hint = "验证码", text = "12")))
    }

    @Test
    fun `treats hint text shown as text as empty`() {
        assertEquals(OtpFillMode.WHOLE_CODE, modeFor(empty.copy(hint = "验证码", text = "验证码", isShowingHint = true)))
    }

    @Test
    fun `skips fields too short for the code`() {
        assertEquals(OtpFillMode.NONE, modeFor(empty.copy(hint = "验证码", maxTextLength = 4)))
    }

    @Test
    fun `skips non-editable nodes`() {
        assertEquals(OtpFillMode.NONE, modeFor(empty.copy(hint = "验证码", isEditable = false)))
    }

    @Test
    fun `types character by character into a row of single-digit boxes on a code screen`() {
        assertEquals(
            OtpFillMode.PER_CHARACTER,
            modeFor(empty.copy(isNumberInput = true, maxTextLength = 1), "请输入验证码"),
        )
    }

    @Test
    fun `ignores single-character fields outside a code screen`() {
        assertEquals(OtpFillMode.NONE, modeFor(empty.copy(maxTextLength = 1), "选择数量"))
    }

    @Test
    fun `does not treat coupon code fields as verification code fields`() {
        assertEquals(OtpFillMode.NONE, modeFor(empty.copy(hint = "请输入优惠码", viewIdResourceName = "app:id/coupon_code")))
    }

    @Test
    fun `fills an untyped web input on a code screen`() {
        val webInput = empty.copy(hint = "", viewIdResourceName = "code")
        assertEquals(OtpFillMode.WHOLE_CODE, modeFor(webInput, "验证码已发送至 138****8000"))
    }

    @Test
    fun `does not fill an untyped web input whose id names another purpose`() {
        val phoneInput = empty.copy(viewIdResourceName = "mobile")
        assertEquals(OtpFillMode.NONE, modeFor(phoneInput, "获取验证码"))
    }

    @Test
    fun `does not fill an untyped web input outside a code screen`() {
        assertEquals(OtpFillMode.NONE, modeFor(empty, "填写收货地址"))
    }

    @Test
    fun `does not treat a hinted native field as untyped`() {
        assertEquals(OtpFillMode.NONE, modeFor(empty.copy(hint = "请输入昵称"), "验证码"))
    }

    companion object {
        private const val CODE = "482913"
    }
}
