package com.danielealbano.androidremotecontrolmcp.services.vault

import java.util.Locale

/** Recognizer language; [code] selects the on-device Vosk model (`?lang=` on the STT socket). */
enum class VoiceLanguage(
    val code: String,
    val displayName: String,
) {
    ZH("zh", "中文"),
    EN("en", "English"),
    EN_IN("en-in", "English (India)"),
    HI("hi", "हिन्दी"),
    ;

    companion object {
        fun forLocale(locale: Locale): VoiceLanguage =
            when {
                locale.language == "zh" -> ZH
                locale.language == "hi" -> HI
                locale.language == "en" && locale.country == "IN" -> EN_IN
                locale.language == "en" -> EN
                else -> ZH
            }
    }
}

/** Language a slot label is written in: the one the user spoke the slot's name in. */
enum class LabelLanguage { ZH, EN, HI }

/** How a spoken value is cleaned up and checked. */
enum class VoiceValueKind {
    /** Mobile or landline number, optionally with a country code. */
    PHONE,

    /** Student / staff / card numbers and postal codes: digits and ASCII letters. */
    NUMBER,

    /** Mainland China resident ID: 17 digits plus a digit or X, checksum verified. */
    ID_CARD,

    /** India Aadhaar: 12 digits, Verhoeff checksum verified. */
    AADHAAR,

    /** India PAN: five letters, four digits, one letter. */
    PAN,

    EMAIL,

    /** Names, addresses, schools: kept as spoken. */
    TEXT,
}

data class VoiceKeyword(
    val text: String,
    val language: LabelLanguage,
)

/** A slot the parser knows by name; [keywords] are what people say for it, in every language. */
data class VoiceSlotSpec(
    val id: String,
    val labels: Map<LabelLanguage, String>,
    val category: VaultCategory,
    val sensitive: Boolean,
    val kind: VoiceValueKind,
    val keywords: List<VoiceKeyword>,
) {
    fun label(language: LabelLanguage): String = labels[language] ?: labels.getValue(LabelLanguage.EN)
}

/**
 * Word lists for [VaultVoiceParser]. Keywords are matched after [VoiceText.fold]. The Hindi model
 * writes English it hears in Devanagari ("my name is" → "माइ नेम इस"), so those spellings are here too.
 */
object VoiceLexicon {
    private fun zh(vararg words: String) = words.map { VoiceKeyword(it, LabelLanguage.ZH) }

    private fun en(vararg words: String) = words.map { VoiceKeyword(it, LabelLanguage.EN) }

    private fun hi(vararg words: String) = words.map { VoiceKeyword(it, LabelLanguage.HI) }

    private fun labels(
        zh: String,
        en: String,
        hi: String,
    ) = mapOf(LabelLanguage.ZH to zh, LabelLanguage.EN to en, LabelLanguage.HI to hi)

    val SLOTS: List<VoiceSlotSpec> =
        listOf(
            VoiceSlotSpec(
                id = "phone",
                labels = labels("手机号", "Phone number", "फ़ोन नंबर"),
                category = VaultCategory.CONTACT,
                sensitive = false,
                kind = VoiceValueKind.PHONE,
                keywords =
                    zh("手机号码", "手机号", "电话号码", "联系电话", "手机", "电话") +
                        en(
                            "phone number",
                            "mobile number",
                            "cell number",
                            "contact number",
                            "mobile no",
                            "phone no",
                            "phone",
                            "mobile",
                            "number",
                        ) +
                        hi(
                            "फोन नंबर",
                            "फोन नम्बर",
                            "मोबाइल नंबर",
                            "मोबाइल नम्बर",
                            "फोन का नंबर",
                            "मोबाइल का नंबर",
                            "फोन",
                            "मोबाइल",
                        ),
            ),
            VoiceSlotSpec(
                id = "emergency_phone",
                labels = labels("紧急联系人电话", "Emergency contact number", "आपातकालीन संपर्क नंबर"),
                category = VaultCategory.CONTACT,
                sensitive = false,
                kind = VoiceValueKind.PHONE,
                keywords =
                    zh("紧急联系人电话", "紧急联系人手机", "紧急联系电话", "紧急联系人") +
                        en("emergency contact number", "emergency contact", "emergency number") +
                        hi("आपातकालीन संपर्क नंबर", "आपातकालीन संपर्क", "इमरजेंसी नंबर"),
            ),
            VoiceSlotSpec(
                id = "name",
                labels = labels("姓名", "Name", "नाम"),
                category = VaultCategory.IDENTITY,
                sensitive = false,
                kind = VoiceValueKind.TEXT,
                keywords =
                    zh("真实姓名", "姓名", "名字", "我叫") +
                        en("full name", "name", "naam", "i'm", "i am") +
                        hi("पूरा नाम", "नाम", "फुल नेम", "नेम"),
            ),
            VoiceSlotSpec(
                id = "student_id",
                labels = labels("学号", "Student ID", "छात्र आईडी"),
                category = VaultCategory.IDENTITY,
                sensitive = false,
                kind = VoiceValueKind.NUMBER,
                keywords =
                    zh("学号") +
                        // "rule" / "role" are how the Indian-English model hears "roll".
                        en(
                            "student id",
                            "student number",
                            "roll number",
                            "rule number",
                            "role number",
                            "roll no",
                            "enrollment number",
                            "enrolment number",
                        ) +
                        hi("रोल नंबर", "रोल नम्बर", "छात्र आईडी", "नामांकन संख्या"),
            ),
            VoiceSlotSpec(
                id = "employee_id",
                labels = labels("工号", "Employee ID", "कर्मचारी आईडी"),
                category = VaultCategory.IDENTITY,
                sensitive = false,
                kind = VoiceValueKind.NUMBER,
                keywords =
                    zh("员工号", "工号") +
                        en("employee id", "employee number", "employee code", "staff id", "staff number") +
                        hi("कर्मचारी आईडी", "कर्मचारी संख्या"),
            ),
            VoiceSlotSpec(
                id = "id_card",
                labels = labels("身份证号", "Resident ID number", "पहचान पत्र संख्या"),
                category = VaultCategory.IDENTITY,
                sensitive = true,
                kind = VoiceValueKind.ID_CARD,
                keywords = zh("身份证号码", "身份证号", "身份证") + en("resident id number", "resident id"),
            ),
            VoiceSlotSpec(
                id = "aadhaar",
                labels = labels("Aadhaar 号码", "Aadhaar number", "आधार नंबर"),
                category = VaultCategory.IDENTITY,
                sensitive = true,
                kind = VoiceValueKind.AADHAAR,
                keywords =
                    en(
                        "aadhaar card number",
                        "aadhaar number",
                        "aadhar number",
                        "adhar number",
                        "aadhaar",
                        "aadhar",
                        "adhar",
                    ) + hi("आधार कार्ड नंबर", "आधार नंबर", "आधार नम्बर", "आधार"),
            ),
            VoiceSlotSpec(
                id = "pan",
                labels = labels("PAN 号码", "PAN", "पैन नंबर"),
                category = VaultCategory.IDENTITY,
                sensitive = true,
                kind = VoiceValueKind.PAN,
                keywords =
                    en("pan card number", "pan number", "pan card", "pan") +
                        hi("पैन कार्ड नंबर", "पैन कार्ड", "पैन नंबर", "पैन"),
            ),
            VoiceSlotSpec(
                id = "postal_code",
                labels = labels("邮编", "Postal code", "पिन कोड"),
                category = VaultCategory.ADDRESS,
                sensitive = false,
                kind = VoiceValueKind.NUMBER,
                keywords =
                    zh("邮政编码", "邮编") +
                        en("pin code", "pincode", "postal code", "post code", "postcode", "zip code", "zip") +
                        hi("पिन कोड", "पिनकोड"),
            ),
            VoiceSlotSpec(
                id = "address",
                labels = labels("收货地址", "Address", "पता"),
                category = VaultCategory.ADDRESS,
                sensitive = false,
                kind = VoiceValueKind.TEXT,
                keywords =
                    zh("收货地址", "家庭住址", "家庭地址", "住址", "地址") +
                        en("home address", "shipping address", "delivery address", "address", "pata") +
                        hi("घर का पता", "पता", "एड्रेस"),
            ),
            VoiceSlotSpec(
                id = "email",
                labels = labels("邮箱", "Email", "ईमेल"),
                category = VaultCategory.CONTACT,
                sensitive = false,
                kind = VoiceValueKind.EMAIL,
                keywords =
                    zh("电子邮箱", "邮箱地址", "邮件地址", "邮箱") +
                        en("email address", "email id", "mail id", "e-mail", "e mail", "email") +
                        hi("ईमेल आईडी", "ईमेल एड्रेस", "ईमेल", "मेल आईडी"),
            ),
            VoiceSlotSpec(
                id = "school",
                labels = labels("学校", "School", "स्कूल"),
                category = VaultCategory.IDENTITY,
                sensitive = false,
                kind = VoiceValueKind.TEXT,
                keywords = zh("学校") + en("school", "college", "university") + hi("स्कूल", "कॉलेज", "विद्यालय"),
            ),
            VoiceSlotSpec(
                id = "company",
                labels = labels("工作单位", "Company", "कंपनी"),
                category = VaultCategory.IDENTITY,
                sensitive = false,
                kind = VoiceValueKind.TEXT,
                keywords = zh("工作单位", "单位") + en("company", "employer", "office") + hi("कंपनी", "ऑफिस", "दफ्तर"),
            ),
        )

    /** Every keyword with its slot, longest first so "手机号码" wins over "手机". */
    val KEYWORDS: List<Pair<VoiceKeyword, VoiceSlotSpec>> =
        SLOTS
            .flatMap { spec -> spec.keywords.map { it to spec } }
            .sortedByDescending { VoiceText.foldAll(it.first.text).length }

    /** Said before the slot name and meaning nothing ("帮我记住…", "my …", "मेरा …"). */
    val LEADING_FILLERS: List<String> =
        listOf(
            "帮我记住",
            "帮我记下",
            "帮我记",
            "帮我",
            "记一下",
            "记住",
            "添加",
            "新增",
            "保存",
            "录入",
            "请",
            "我的",
            "please",
            "add",
            "save",
            "remember",
            "store",
            "my",
            "the",
            "मेरा",
            "मेरी",
            "मेरे",
            "mera",
            "meri",
            "mere",
            "माइ",
            "माय",
        )

    /** Between the name and the value ("是", "is", "है"). */
    val SEPARATORS: List<String> =
        listOf(
            "号码是",
            "号是",
            "是",
            "为",
            "叫",
            ":",
            "=",
            ",",
            "，",
            "：",
            "is",
            "was",
            "equals",
            "hai",
            "ka",
            "ki",
            "है",
            "का",
            "की",
            "के",
            "इज",
            "इस",
        )

    /** Said after the value in Hindi word order ("… है") or as sentence end. */
    val TRAILING_WORDS: List<String> = listOf("है", "हैं", "hai", "hain", "।", "。", ".", "!", "！", "?", "？")

    /**
     * Never recorded by voice: saying a password or one-time code aloud leaks it to anyone nearby,
     * and credentials need the password-field rules of the manual editor. Matched with
     * [VoiceText.containsPhrase]: Chinese as substrings, other scripts as whole words.
     */
    val FORBIDDEN_PHRASES: List<String> =
        listOf(
            "密码",
            "口令",
            "验证码",
            "支付码",
            "password",
            "passcode",
            "passwd",
            "otp",
            "cvv",
            "pin",
            "पासवर्ड",
            "ओटीपी",
            "पिन",
        )

    /** "PIN code" in India is the postal code, not a secret. */
    val PIN_CODE_PHRASES: List<String> = listOf("pin code", "pincode", "पिन कोड", "पिनकोड")

    /** Slot names that ask for confirmation on every fill; matched like [FORBIDDEN_PHRASES]. */
    val SENSITIVE_PHRASES: List<String> =
        listOf(
            "银行卡",
            "信用卡",
            "身份证",
            "护照",
            "社保",
            "医保",
            "bank",
            "credit card",
            "debit card",
            "passport",
            "aadhaar",
            "aadhar",
            "pan",
            "बैंक",
            "पासपोर्ट",
            "आधार",
            "पैन",
        )
}
