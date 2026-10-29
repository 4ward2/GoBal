package com.danielealbano.androidremotecontrolmcp.services.assist

/** How a verification code is put into a field. */
enum class OtpFillMode {
    /** Leave the field alone. */
    NONE,

    /** Set the whole code at once (falls back to committing it through the input method). */
    WHOLE_CODE,

    /** Commit one character at a time through the input method (row of single-digit boxes). */
    PER_CHARACTER,
}

/** Plain snapshot of an input field, free of Android types for JVM unit tests. */
data class OtpFieldFacts(
    val isEditable: Boolean,
    val isPassword: Boolean,
    val isShowingHint: Boolean,
    val text: String?,
    val hint: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?,
    val isNumberInput: Boolean,
    val maxTextLength: Int,
)

/**
 * Decides whether the focused field should receive an SMS verification code.
 *
 * A field qualifies when it is editable, empty, not a password field, not an image-captcha field,
 * and either (a) its own hint/id/description says "verification code", or (b) it is a short
 * numeric field on a screen that talks about verification codes (the 4–6 box OTP widgets usually
 * have no hint of their own), or (c) it is a web input exposing no type information at all, on a
 * code screen, whose id does not name another purpose. Rows of single-character boxes are typed
 * one character at a time, because each box only holds one digit and the app moves focus to the
 * next box itself.
 */
object OtpFieldRules {
    private const val MIN_OTP_LENGTH = 4
    private const val MAX_OTP_LENGTH = 8

    /** A bare "code" is deliberately NOT a cue: coupon / promo / invite code fields use it too. */
    private val FIELD_CUE =
        Regex(
            "验证码|驗證碼|校验码|动态码|动态密码|短信码|确认码|sms|otp|one[-_ ]?time|verif|passcode|vcode|" +
                "auth_?code|check_?code|valid_?code|security_?code",
            RegexOption.IGNORE_CASE,
        )

    /** Image / slider captchas must never receive an SMS code. */
    private val IMAGE_CAPTCHA_CUE = Regex("图形|圖形|图片|圖片|captcha|image|img|pic|滑块", RegexOption.IGNORE_CASE)

    /** Labels of fields that are clearly something else, even on a code screen. */
    private val OTHER_FIELD_CUE =
        Regex(
            "手机|电话|phone|mobile|tel|昵称|姓名|name|邮箱|email|mail|地址|address|搜索|search|" +
                "优惠|coupon|promo|邀请|invite|备注|remark|comment|留言",
            RegexOption.IGNORE_CASE,
        )

    private val SCREEN_CUE =
        Regex(
            "验证码|驗證碼|校验码|动态码|获取验证码|重新获取|重新发送|秒后重|s后重|已发送至|已發送至|verification code|resend",
            RegexOption.IGNORE_CASE,
        )

    /** How [field] should receive [code], given the visible [screenText]. */
    fun fillMode(
        field: OtpFieldFacts,
        code: String,
        screenText: String,
    ): OtpFillMode {
        val ownLabels = listOfNotNull(field.hint, field.contentDescription, field.viewIdResourceName).joinToString(" ")
        val isCodeContext = FIELD_CUE.containsMatchIn(ownLabels) || SCREEN_CUE.containsMatchIn(screenText)
        return when {
            !isFillable(field, ownLabels) -> OtpFillMode.NONE
            field.maxTextLength == 1 && isCodeContext -> OtpFillMode.PER_CHARACTER
            field.maxTextLength in 1 until code.length -> OtpFillMode.NONE
            FIELD_CUE.containsMatchIn(ownLabels) -> OtpFillMode.WHOLE_CODE
            isCodeBox(field) && isCodeContext -> OtpFillMode.WHOLE_CODE
            isUntypedField(field, ownLabels) && SCREEN_CUE.containsMatchIn(screenText) -> OtpFillMode.WHOLE_CODE
            else -> OtpFillMode.NONE
        }
    }

    /** Editable, empty, not a password and not an image captcha. */
    private fun isFillable(
        field: OtpFieldFacts,
        ownLabels: String,
    ): Boolean {
        val isEmpty = field.isShowingHint || field.text.isNullOrEmpty() || field.text == field.hint
        return field.isEditable && !field.isPassword && isEmpty && !IMAGE_CAPTCHA_CUE.containsMatchIn(ownLabels)
    }

    /**
     * Web inputs (WebView / H5 pages) expose no hint, input type or max length at all; such a field
     * qualifies only when its own id/labels do not name something else.
     */
    private fun isUntypedField(
        field: OtpFieldFacts,
        ownLabels: String,
    ): Boolean {
        val exposesNoType = !field.isNumberInput && field.maxTextLength <= 0 && field.hint.isNullOrBlank()
        return exposesNoType && !OTHER_FIELD_CUE.containsMatchIn(ownLabels)
    }

    private fun isCodeBox(field: OtpFieldFacts): Boolean {
        val lengthFits = field.maxTextLength in MIN_OTP_LENGTH..MAX_OTP_LENGTH
        return field.isNumberInput || lengthFits
    }
}
