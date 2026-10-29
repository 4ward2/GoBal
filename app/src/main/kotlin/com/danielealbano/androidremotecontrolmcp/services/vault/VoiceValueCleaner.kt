package com.danielealbano.androidremotecontrolmcp.services.vault

/** A value ready to store, plus a plain-language doubt about it when the checks failed. */
data class CleanedValue(
    val value: String,
    val warning: String?,
) {
    override fun toString(): String = "CleanedValue(value=<redacted>, warning=$warning)"
}

/** Per-kind clean-up and checks for a spoken value. Warnings are advisory: the user decides. */
object VoiceValueCleaner {
    private val PAN_SHAPE = Regex("[A-Z]{5}\\d{4}[A-Z]")
    private val EMAIL_SHAPE = Regex("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}")

    internal val DASH_WORDS = setOf("dash", "hyphen", "-", "杠", "横杠", "डैश")
    private val ID_CARD_X = Regex("(叉|艾克斯|爱克斯|ex|x|एक्स)$")

    private const val ID_CARD_LENGTH = 18
    private const val OLD_ID_CARD_LENGTH = 15
    private const val AADHAAR_LENGTH = 12

    internal const val UNHEARD = "有几个词没听成数字，请核对"

    /** @return null when a spoken number could not be read at all (mixed digit and unit styles). */
    fun clean(
        kind: VoiceValueKind,
        spoken: String,
    ): CleanedValue? =
        when (kind) {
            VoiceValueKind.PHONE -> PhoneValues.clean(spoken)
            VoiceValueKind.NUMBER -> number(spoken)
            VoiceValueKind.ID_CARD -> idCard(spoken)
            VoiceValueKind.AADHAAR -> aadhaar(spoken)
            VoiceValueKind.PAN -> pan(spoken)
            VoiceValueKind.EMAIL -> email(spoken)
            VoiceValueKind.TEXT -> CleanedValue(VoiceEdits.capitalizeLatinWords(VoiceEdits.tidySpaces(spoken)), null)
        }

    /** Digits and single letters ("a b c one two" → "ABC12"); dash words become "-". */
    private fun number(spoken: String): CleanedValue? {
        val tokens = SpokenNumbers.convert(spoken)?.split(' ')?.filter { it.isNotEmpty() } ?: return null
        var unknown = false
        val value =
            tokens.joinToString("") { t ->
                when {
                    t in DASH_WORDS -> {
                        "-"
                    }

                    t.all { it.isAsciiDigit() } -> {
                        t
                    }

                    t.length == 1 && t[0].isAsciiLetter() -> {
                        t.uppercase()
                    }

                    t.all { it.isAsciiDigit() || it.isAsciiLetter() } && t.any { it.isAsciiDigit() } -> {
                        t.uppercase()
                    }

                    else -> {
                        unknown = true
                        ""
                    }
                }
            }
        return CleanedValue(value, if (unknown) "有几个词没听成数字或字母，请核对" else null)
    }

    private fun idCard(spoken: String): CleanedValue? {
        val converted = SpokenNumbers.convert(spoken)?.replace(" ", "") ?: return null
        val withX = ID_CARD_X.replace(converted, "X")
        val value = withX.filter { it.isAsciiDigit() || it == 'X' }
        val warning =
            when {
                value.length != withX.length -> UNHEARD
                value.length == OLD_ID_CARD_LENGTH && value.all { it.isAsciiDigit() } -> null
                value.length != ID_CARD_LENGTH -> "听到 ${value.length} 位，身份证号应该是 18 位，请核对"
                !Checksums.chineseIdOk(value) -> "最后一位校验不对，可能有数字听错了，请核对"
                else -> null
            }
        return CleanedValue(value, warning)
    }

    private fun aadhaar(spoken: String): CleanedValue? {
        val converted = SpokenNumbers.convert(spoken)?.replace(" ", "") ?: return null
        val value = converted.filter { it.isAsciiDigit() }
        val warning =
            when {
                value.length != converted.length -> UNHEARD
                value.length != AADHAAR_LENGTH -> "听到 ${value.length} 位，Aadhaar 号码应该是 12 位，请核对"
                !Checksums.verhoeffOk(value) || value[0] == '0' || value[0] == '1' -> "校验不对，可能有数字听错了，请核对"
                else -> null
            }
        return CleanedValue(value, warning)
    }

    private fun pan(spoken: String): CleanedValue? =
        number(spoken)?.let { n ->
            n.copy(warning = n.warning ?: "PAN 应该是 5 个字母、4 位数字、1 个字母，请核对".takeUnless { PAN_SHAPE.matches(n.value) })
        }

    private val EMAIL_WORDS: List<Pair<String, String>> =
        listOf(
            "at the rate of" to "@",
            "at the rate" to "@",
            "at the rage" to "@",
            "at rate" to "@",
            "एट द रेट" to "@",
            "ऐट द रेट" to "@",
            "at" to "@",
            "艾特" to "@",
            "圈a" to "@",
            "dot" to ".",
            "डॉट" to ".",
            "点" to ".",
            "underscore" to "_",
            "अंडरस्कोर" to "_",
            "下划线" to "_",
            "dash" to "-",
            "hyphen" to "-",
            "横杠" to "-",
            "杠" to "-",
            "g mail" to "gmail",
            "जीमेल" to "gmail",
            "कॉम" to "com",
            "याहू" to "yahoo",
            "आउटलुक" to "outlook",
            "हॉटमेल" to "hotmail",
        )

    /** Recognizer slips measured on the Indian-English model ("gmail" heard as "female"). */
    private val DOMAIN_FIXES = mapOf("female" to "gmail", "email" to "gmail")

    private fun email(spoken: String): CleanedValue? {
        var text = " $spoken "
        EMAIL_WORDS.forEach { (said, symbol) ->
            text =
                if (VoiceText.isCjk(said.first())) {
                    text.replace(said, " $symbol ")
                } else {
                    text.replace(" $said ", " $symbol ")
                }
        }
        val converted = SpokenNumbers.convert(text.trim())?.replace(" ", "") ?: return null
        val at = converted.indexOf('@')
        val value =
            if (at < 0) {
                converted
            } else {
                val domain = converted.substring(at + 1)
                val fixed = DOMAIN_FIXES.entries.firstOrNull { domain.startsWith(it.key + ".") }
                converted.substring(0, at + 1) + (fixed?.let { it.value + domain.removePrefix(it.key) } ?: domain)
            }
        return CleanedValue(value, if (EMAIL_SHAPE.matches(value)) null else "邮箱格式看起来不对，请核对")
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
}

/** Phone numbers: country codes, Chinese / Indian / landline shapes. */
internal object PhoneValues {
    private val CN_MOBILE = Regex("1[3-9]\\d{9}")
    private val TEN_DIGIT_MOBILE = Regex("[2-9]\\d{9}")
    private val CN_LANDLINE = Regex("0\\d{9,11}")
    private val LOCAL_NUMBER = Regex("\\d{7,8}")
    private val INTERNATIONAL = Regex("\\+\\d{8,15}")
    private val PLUS_WORDS = setOf("plus", "+", "加", "प्लस")
    private val DASH_WORDS get() = VoiceValueCleaner.DASH_WORDS
    private const val CN_COUNTRY = "86"
    private const val IN_COUNTRY = "91"
    private const val CN_MOBILE_LENGTH = 11
    private const val IN_MOBILE_LENGTH = 10

    private val LEADING_PLUS = Regex("^(加|प्लस) *")

    fun clean(spoken: String): CleanedValue? {
        val prepared = LEADING_PLUS.replace(spoken, "+ ")
        val tokens = SpokenNumbers.convert(prepared)?.split(' ')?.filter { it.isNotEmpty() } ?: return null
        val plus = tokens.firstOrNull()?.let { it in PLUS_WORDS || it.startsWith("+") } == true
        val digits = tokens.joinToString("").filter { it in '0'..'9' }
        val value = withoutCountryCode(digits) ?: if (plus) "+$digits" else digits
        val warning = if (tokens.all(::isPhoneToken)) phoneWarning(value) else VoiceValueCleaner.UNHEARD
        return CleanedValue(value, warning)
    }

    /** +86 / +91 numbers are stored the way local forms expect them; null when there is no such code. */
    private fun withoutCountryCode(digits: String): String? =
        when {
            digits.length == CN_MOBILE_LENGTH + 2 && digits.startsWith(CN_COUNTRY) -> digits.drop(2)
            digits.length == IN_MOBILE_LENGTH + 2 && digits.startsWith(IN_COUNTRY) -> digits.drop(2)
            else -> null
        }

    private fun phoneWarning(value: String): String? {
        val bare = value.removePrefix("+")
        val known = listOf(CN_MOBILE, TEN_DIGIT_MOBILE, CN_LANDLINE, LOCAL_NUMBER).any { it.matches(bare) }
        return when {
            known || INTERNATIONAL.matches(value) -> null
            bare.startsWith("1") -> "听到 ${bare.length} 位，中国手机号应该是 11 位，请核对"
            else -> "听到 ${bare.length} 位数字，号码格式看起来不对，请核对"
        }
    }

    private fun isPhoneToken(t: String): Boolean = t in PLUS_WORDS || t in DASH_WORDS || t.trimStart('+').isAllDigits()

    private fun String.isAllDigits(): Boolean = all { it in '0'..'9' }
}

/** Check digits of identity numbers, so a misheard digit is caught before it is saved. */
object Checksums {
    private val CN_WEIGHTS = "7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2".split(',').map(String::toInt)
    private const val CN_CHECK_CHARS = "10X98765432"

    fun chineseIdOk(id: String): Boolean {
        val body = id.dropLast(1)
        val wellFormed = id.length == CN_WEIGHTS.size + 1 && body.all { it in '0'..'9' }
        val sum = if (wellFormed) body.mapIndexed { i, c -> (c - '0') * CN_WEIGHTS[i] }.sum() else 0
        return wellFormed && CN_CHECK_CHARS[sum % CN_CHECK_CHARS.length] == id.last()
    }

    /** Verhoeff multiplication table (dihedral group D5), one row per string. */
    private val VERHOEFF_D =
        listOf(
            "0123456789",
            "1234067895",
            "2340178956",
            "3401289567",
            "4012395678",
            "5987604321",
            "6598710432",
            "7659821043",
            "8765932104",
            "9876543210",
        ).map { row -> row.map { it - '0' } }

    /** Verhoeff permutation table. */
    private val VERHOEFF_P =
        listOf(
            "0123456789",
            "1576283094",
            "5803796142",
            "8916043527",
            "9453128760",
            "4286573901",
            "2793806415",
            "7046913258",
        ).map { row -> row.map { it - '0' } }

    /** Verhoeff check used by Aadhaar; the last digit is the check digit. */
    fun verhoeffOk(digits: String): Boolean {
        var c = 0
        val wellFormed = digits.isNotEmpty() && digits.all { it in '0'..'9' }
        if (wellFormed) {
            digits.reversed().forEachIndexed { i, ch ->
                c = VERHOEFF_D[c][VERHOEFF_P[i % VERHOEFF_P.size][ch - '0']]
            }
        }
        return wellFormed && c == 0
    }
}
