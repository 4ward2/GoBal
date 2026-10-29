package com.danielealbano.androidremotecontrolmcp.services.assist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@DisplayName("OtpExtractor")
class OtpExtractorTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "【美团】您的验证码是482913，5分钟内有效，请勿泄露。|482913",
            "【淘宝】验证码 7302，用于登录，10分钟内有效。|7302",
            "【闲鱼】123456是您的登录验证码，若非本人操作请忽略。|123456",
            "【麦当劳】您的动态码为：889900，有效期5分钟|889900",
            "【肯德基】您正在登录，校验码 5521 。|5521",
            "Your verification code is 604817. It expires in 10 minutes.|604817",
            "【XX银行】您尾号6789的账户正在登录，验证码 314159，有效期2分钟。|314159",
            "【某平台】订单 2026-09-24 已支付¥1999，验证码 2468|2468",
            "Use code A7K9Q2 to sign in|A7K9Q2",
        ],
    )
    fun `extracts the code nearest to the keyword`(
        text: String,
        expected: String,
    ) {
        assertEquals(expected, OtpExtractor.extract(text))
    }

    @Test
    fun `ignores texts without a verification keyword`() {
        assertNull(OtpExtractor.extract("【美团】您的订单 482913 已送达，祝您用餐愉快"))
    }

    @Test
    fun `ignores validity periods and amounts`() {
        assertNull(OtpExtractor.extract("验证码将在 5分钟 后失效，本次消费 1200元"))
    }

    @Test
    fun `ignores numbers that are part of longer runs such as phone numbers`() {
        assertNull(OtpExtractor.extract("验证码已发送至 13800138000"))
    }

    @Test
    fun `returns null for blank input`() {
        assertNull(OtpExtractor.extract(null))
        assertNull(OtpExtractor.extract("   "))
    }
}
