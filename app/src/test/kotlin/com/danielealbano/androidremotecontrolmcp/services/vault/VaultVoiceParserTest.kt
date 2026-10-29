package com.danielealbano.androidremotecontrolmcp.services.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@DisplayName("VaultVoiceParser")
class VaultVoiceParserTest {
    private fun parsed(
        transcript: String,
        existing: List<VaultSlot> = emptyList(),
    ): VoiceDraft {
        val result = VaultVoiceParser.parse(transcript, existing)
        assertTrue(result is VoiceParseResult.Parsed, "expected Parsed for '$transcript' but got $result")
        return (result as VoiceParseResult.Parsed).draft
    }

    private fun failed(transcript: String): String {
        val result = VaultVoiceParser.parse(transcript, emptyList())
        assertTrue(result is VoiceParseResult.Failed, "expected Failed for '$transcript' but got $result")
        return (result as VoiceParseResult.Failed).reason
    }

    private fun slot(
        id: String,
        label: String,
        category: VaultCategory = VaultCategory.CONTACT,
        sensitive: Boolean = false,
    ) = VaultSlot(id, label, category, sensitive, emptyList(), hasValue = true)

    @Nested
    @DisplayName("Chinese, colloquial and dialect readings")
    inner class Chinese {
        @Test
        fun `measured vosk output with an extra digit is joined and flagged`() {
            // Measured: "幺三八 幺二三四 五六七八" came back from vosk-model-small-cn like this (one 三 too many).
            val d = parsed("手机号 一 三 三八 一 二 三 四 五六七八")
            assertEquals("phone", d.slotId)
            assertEquals("手机号", d.label)
            assertEquals("133812345678", d.value)
            assertNotNull(d.warning)
        }

        @ParameterizedTest
        @CsvSource(
            "手机号 幺三八幺二三四五六七八, 13812345678",
            "电话 幺 三 八 腰 二 三 四 五 六 七 八, 13812345678",
            "我的手机号是一三八一二三四五六七八, 13812345678",
            "帮我记住 手机号码 一三八 一二三四 五六七八, 13812345678",
            "手机 加八六 一三八一二三四五六七八, 13812345678",
            "手机号 一三八 一二三四 五六 两个七, 13812345677",
        )
        fun `colloquial digits are read`(
            spoken: String,
            expected: String,
        ) {
            assertEquals(expected, parsed(spoken).value)
        }

        @Test
        fun `radio readings 洞 拐 勾 and repeats 三个八 are digits`() {
            assertEquals("2021888070", parsed("学号 二零二一 三个八 洞拐洞").value)
            assertEquals("888", parsed("学号 三 个 八").value)
            assertEquals("0797", parsed("工号 洞拐勾拐").value)
        }

        @Test
        fun `numbers read with units are understood`() {
            assertEquals("105", parsed("工号 一百零五").value)
            assertEquals("3500", parsed("工号 三千五").value)
            assertEquals("1010", parsed("工号 一千零十").value)
        }

        @Test
        fun `mixed digit and unit reading asks to read digit by digit`() {
            assertTrue(failed("学号 二零二一百").contains("一位一位"))
        }

        @Test
        fun `a short mobile number gets a warning but is still offered`() {
            val d = parsed("手机号 一三八一二三四五六七")
            assertEquals("1381234567", d.value)
            assertNotNull(d.warning)
        }

        @Test
        fun `name and address keep the text and drop recognizer spaces`() {
            assertEquals("张三", parsed("姓名 张 三").value)
            assertEquals("张三", parsed("我叫张三").value)
            assertEquals("北京市海淀区中关村大街二十七号", parsed("地址 北京市 海淀区 中关村 大街 二十七号").value)
            assertEquals("address", parsed("收货地址是 北京市").slotId)
        }

        @Test
        fun `id card with trailing 叉 and valid checksum has no warning`() {
            val d = parsed("身份证号 一一零一零五 一九四九 一零零零 零零一 叉")
            assertEquals("11010519491000001X", d.value)
            assertTrue(d.sensitive)
            assertNull(d.warning)
        }

        @Test
        fun `id card with a misheard digit is flagged by the checksum`() {
            assertNull(parsed("身份证 一一零一零五一九四九一零零一零零二三").warning)
            assertNotNull(parsed("身份证 一一零一零五一九四九一零零一零零二四").warning)
        }

        @Test
        fun `unknown chinese label splits before the first digit`() {
            val d = parsed("借书证号 一二三四五")
            assertEquals("借书证号", d.label)
            assertEquals("12345", d.value)
            assertEquals(VoiceValueKind.NUMBER, d.kind)
            assertEquals("item", d.slotId)
        }

        @Test
        fun `a label starting with a digit character still splits at the value`() {
            val d = parsed("一卡通号 一二三")
            assertEquals("一卡通号", d.label)
            assertEquals("123", d.value)
        }

        @Test
        fun `unknown chinese label with 是 separator`() {
            val d = parsed("宠物名字是旺财")
            assertEquals("宠物名字", d.label)
            assertEquals("旺财", d.value)
            assertEquals(VoiceValueKind.TEXT, d.kind)
        }
    }

    @Nested
    @DisplayName("English and Indian English")
    inner class English {
        @Test
        fun `vosk en-in output with homophones and double sevens`() {
            // Measured: "nine eight four five double seven three two one oh" came back like this.
            val d = parsed("my phone number is nine eight four for five double sevens three two one oh")
            assertEquals("phone", d.slotId)
            assertEquals("Phone number", d.label)
            assertEquals("98445773210", d.value)
        }

        @Test
        fun `indian mobile with country code is stored nationally`() {
            val spoken = "mobile number plus nine one nine eight four five double seven three two one oh"
            assertEquals("9845773210", parsed(spoken).value)
            assertNull(parsed("phone nine eight four five seven seven three two one zero").warning)
        }

        @Test
        fun `tens and hundreds as indians often read numbers`() {
            assertEquals("9845773210", parsed("phone ninety eight forty five seventy seven thirty two ten").value)
            assertEquals("900", parsed("employee id nine hundred").value)
        }

        @Test
        fun `roll number heard as rule number goes to student id`() {
            val d = parsed("rule number two zero to one triple five")
            assertEquals("student_id", d.slotId)
            assertEquals("2021555", d.value)
        }

        @Test
        fun `email said the indian way`() {
            val d = parsed("email john dot smith at the rate gmail dot com")
            assertEquals("john.smith@gmail.com", d.value)
            assertNull(d.warning)
        }

        @Test
        fun `email with recognizer slip female for gmail`() {
            assertEquals("johnsmith@gmail.com", parsed("email john smith at the rate female dot com").value)
        }

        @Test
        fun `name is capitalised`() {
            assertEquals("John Smith", parsed("my name is john smith").value)
            assertEquals("name", parsed("i am john").slotId)
        }

        @Test
        fun `aadhaar is sensitive and verhoeff checked`() {
            val ok = parsed("aadhaar number two three four one two three four one two three four six")
            assertEquals("234123412346", ok.value)
            assertTrue(ok.sensitive)
            assertNull(ok.warning)
            assertNotNull(parsed("aadhaar two three four one two three four one two three four five").warning)
        }

        @Test
        fun `pan letters and digits`() {
            val d = parsed("pan card a b c d e one two three four f")
            assertEquals("ABCDE1234F", d.value)
            assertNull(d.warning)
        }

        @Test
        fun `pin code is a postal code, not a secret`() {
            val d = parsed("pin code five six zero zero zero one")
            assertEquals("postal_code", d.slotId)
            assertEquals("560001", d.value)
        }

        @Test
        fun `custom english label with is`() {
            val d = parsed("library card number is one two three")
            assertEquals("Library card number", d.label)
            assertEquals("123", d.value)
            assertEquals(VoiceValueKind.NUMBER, d.kind)
        }

        @Test
        fun `company does not count as pan`() {
            val d = parsed("company acme")
            assertEquals("company", d.slotId)
            assertFalse(d.sensitive)
        }
    }

    @Nested
    @DisplayName("Hindi")
    inner class Hindi {
        @Test
        fun `devanagari digits in hindi word order`() {
            val d = parsed("मेरा फ़ोन नंबर नौ आठ चार पांच सात सात तीन दो एक शून्य है")
            assertEquals("phone", d.slotId)
            assertEquals("फ़ोन नंबर", d.label)
            assertEquals("9845773210", d.value)
            assertNull(d.warning)
        }

        @Test
        fun `nukta and chandrabindu spellings match either way`() {
            assertEquals("9845773210", parsed("फोन नम्बर नौ आठ चार पाँच डबल सात तीन दो एक ज़ीरो").value)
        }

        @Test
        fun `romanized hinglish digits`() {
            assertEquals("9845", parsed("mobile nau aath char paanch").value)
        }

        @Test
        fun `hindi name and address`() {
            assertEquals("राहुल शर्मा", parsed("मेरा नाम राहुल शर्मा है").value)
            assertEquals("address", parsed("पता बारह एमजी रोड").slotId)
        }

        @Test
        fun `english transliterated by the hindi model`() {
            val d = parsed("माइ नेम इस राहुल")
            assertEquals("name", d.slotId)
            assertEquals("राहुल", d.value)
        }

        @Test
        fun `devanagari numerals are digits`() {
            assertEquals("9845", parsed("फोन ९८४५").value)
        }

        @Test
        fun `aadhaar in hindi`() {
            val d = parsed("आधार नंबर दो तीन चार एक दो तीन चार एक दो तीन चार छह")
            assertEquals("aadhaar", d.slotId)
            assertEquals("आधार नंबर", d.label)
            assertEquals("234123412346", d.value)
        }
    }

    @Nested
    @DisplayName("safety and updates")
    inner class Safety {
        @ParameterizedTest
        @CsvSource(
            "支付宝密码 一二三四五六",
            "验证码 三八二九",
            "my password is abc",
            "bank pin one two three four",
            "ओटीपी एक दो तीन",
        )
        fun `secrets are refused`(spoken: String) {
            assertTrue(failed(spoken).contains("不要用语音"))
        }

        @Test
        fun `empty and label-only input fail with a hint`() {
            assertTrue(failed("  ").contains("没有听到"))
            assertTrue(failed("手机号").contains("没听到内容"))
            assertTrue(failed("随便说点什么").contains("名称"))
        }

        @Test
        fun `known slot updates the existing entry and keeps its label and sensitivity`() {
            val existing = listOf(slot("phone", "我的手机", sensitive = true))
            val d = parsed("手机号 一三八一二三四五六七八", existing)
            assertEquals("phone", d.slotId)
            assertEquals("我的手机", d.label)
            assertTrue(d.sensitive)
            assertEquals(existing[0], d.replaces)
        }

        @Test
        fun `same custom label updates, a new one gets a fresh id`() {
            val existing = listOf(slot("item", "借书证号", VaultCategory.OTHER))
            assertEquals("item", parsed("借书证号 九九九", existing).slotId)
            assertEquals("item_2", parsed("会员卡号 九九九", existing).slotId)
        }

        @Test
        fun `a credential slot is never overwritten by voice`() {
            val existing = listOf(slot("phone", "手机号", VaultCategory.CREDENTIAL))
            val result = VaultVoiceParser.parse("手机号 一三八一二三四五六七八", existing)
            assertTrue(result is VoiceParseResult.Failed)
        }

        @Test
        fun `draft toString never shows the value`() {
            assertFalse(parsed("手机号 一三八一二三四五六七八").toString().contains("138"))
        }
    }

    @Nested
    @DisplayName("checksums and numerals")
    inner class Units {
        @Test
        fun `chinese id checksum`() {
            assertTrue(Checksums.chineseIdOk("11010519491000001X"))
            assertTrue(Checksums.chineseIdOk("110105194910010023"))
            assertFalse(Checksums.chineseIdOk("110105194910010021"))
        }

        @Test
        fun `verhoeff`() {
            assertTrue(Checksums.verhoeffOk("2363"))
            assertFalse(Checksums.verhoeffOk("2364"))
        }

        @ParameterizedTest
        @CsvSource("十五, 15", "二十, 20", "一百二十三, 123", "一万二, 12000", "两万三千, 23000", "一万零五, 10005", "十万, 100000")
        fun `numerals with units`(
            spoken: String,
            expected: Long,
        ) {
            assertEquals(expected, ChineseNumerals.parseWithUnits(spoken))
        }

        @ParameterizedTest
        @CsvSource("百", "万", "十百", "二十三十", "一百二三")
        fun `malformed numerals`(spoken: String) {
            assertNull(ChineseNumerals.parseWithUnits(spoken))
        }

        @Test
        fun `oh alone is a word, next to digits a zero`() {
            assertEquals("oh nice", SpokenNumbers.convert("oh nice"))
            assertEquals("102", SpokenNumbers.convert("one oh two"))
        }
    }
}
