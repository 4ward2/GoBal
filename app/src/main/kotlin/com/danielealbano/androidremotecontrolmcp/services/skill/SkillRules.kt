package com.danielealbano.androidremotecontrolmcp.services.skill

/**
 * Replays never spend money: a tap on a control that submits an order or pays is not made, the
 * run stops there and the user takes over, however the skill was recorded or written.
 */
object PaymentGuard {
    private val KEYWORDS =
        listOf(
            "提交订单",
            "确认下单",
            "立即下单",
            "去支付",
            "立即支付",
            "确认支付",
            "确认付款",
            "立即付款",
            "支付",
            "付款",
            "买单",
            "pay now",
            "place order",
            "submit order",
            "confirm payment",
        )

    fun blocks(label: String): Boolean {
        val squeezed = SelectorMatcher.squeeze(label)
        return squeezed.isNotEmpty() && KEYWORDS.any { squeezed.contains(SelectorMatcher.squeeze(it)) }
    }
}

/** A skill picked by what the user said, with the parameter values read out of the sentence. */
data class SkillMatch(
    val skill: Skill,
    val params: Map<String, String>,
)

/**
 * Local rule: a sentence runs a skill when it *is* the skill's name, allowing polite fillers and
 * `{{param}}` slots ("点{{店名}}外卖" ← "帮我点瑞幸外卖"). No language model decides this.
 */
object SkillMatcher {
    private val FILLERS = listOf("麻烦你", "麻烦", "帮我", "帮忙", "给我", "替我", "请你", "请", "我要", "我想", "please")

    /** Lower case without spaces, punctuation or symbols, except the braces of `{{param}}`. */
    fun key(text: String): String = compact(text).lowercase()

    /** Like [key] but keeping case: parameter values are read out of this ("WLAN", not "wlan"). */
    private fun compact(text: String): String {
        val keep = { c: Char -> c == '{' || c == '}' || !(c.isWhitespace() || isPunctuationOrSymbol(c)) }
        return text.filter(keep)
    }

    private fun isPunctuationOrSymbol(c: Char): Boolean =
        when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
            Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
            -> true

            else -> false
        }

    fun match(
        utterance: String,
        skills: List<Skill>,
    ): SkillMatch? {
        val said = stripFillers(compact(utterance))
        if (said.isEmpty()) return null
        return skills
            .mapNotNull { skill -> matchOne(said, skill)?.let { skill to it } }
            // Fixed names first, then the pattern with the most literal text (the most specific one).
            .minWithOrNull(
                compareBy<Pair<Skill, Map<String, String>>> { it.second.size }
                    .thenByDescending { literalLength(it.first.name) },
            )?.let { (skill, params) -> SkillMatch(skill, params) }
    }

    private fun matchOne(
        said: String,
        skill: Skill,
    ): Map<String, String>? {
        val name = stripFillers(compact(skill.name))
        // Names come from the original text: the key folds case and drops punctuation inside them.
        val names = SkillParams.namesIn(skill.name)
        if (names.isEmpty()) return emptyMap<String, String>().takeIf { name.equals(said, ignoreCase = true) }
        val parts = name.split(SkillParams.ANY_PLACEHOLDER)
        val pattern = parts.joinToString("(.+?)") { Regex.escape(it) }
        return Regex(pattern, RegexOption.IGNORE_CASE).matchEntire(said)?.let { m ->
            names.withIndex().associate { (i, n) -> n to m.groupValues[i + 1] }
        }
    }

    private fun literalLength(name: String): Int = key(name).replace(SkillParams.ANY_PLACEHOLDER, "").length

    private fun stripFillers(text: String): String {
        var t = text
        var changed = true
        while (changed) {
            changed = false
            FILLERS.firstOrNull { t.startsWith(it, ignoreCase = true) && t.length > it.length }?.let {
                t = t.drop(it.length)
                changed = true
            }
        }
        return t
    }
}

/** What a skill must satisfy before it is stored. */
object SkillValidator {
    /** A task saved by the agent is named by the user's whole request, which can be a long sentence. */
    const val MAX_NAME = 120
    const val MAX_STEPS = 80
    const val MAX_TEXT = 500
    const val MAX_COLLECT_ITEMS = 1_000
    const val MAX_COLLECT_SCROLLS = 200
    const val MIN_PAUSE_MS = 300L
    const val MAX_PAUSE_MS = 30_000L
    private val PACKAGE = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")

    fun problems(skill: Skill): List<String> {
        val out = mutableListOf<String>()
        val bareName = SkillMatcher.key(skill.name).replace(SkillParams.ANY_PLACEHOLDER, "")
        if (bareName.isEmpty()) out += "技能名称不能为空，也不能只有参数"
        if (skill.name.length > MAX_NAME) out += "技能名称最多 $MAX_NAME 个字"
        if (skill.steps.isEmpty()) out += "技能至少要有一步"
        if (skill.steps.size > MAX_STEPS) out += "技能最多 $MAX_STEPS 步"
        val declared = skill.params.map { it.name }.toSet()
        val undeclared = SkillParams.namesUsed(skill).filterNot { it in declared }
        if (undeclared.isNotEmpty()) out += "参数没有声明：${undeclared.joinToString("、")}"
        if (skill.params
                .map { it.name }
                .distinct()
                .size != skill.params.size
        ) {
            out += "参数名重复"
        }
        skill.steps.forEachIndexed { i, step -> stepProblem(step)?.let { out += "第 ${i + 1} 步：$it" } }
        return out
    }

    private fun stepProblem(step: SkillStep): String? =
        when (step) {
            is SkillStep.Launch -> {
                if (PACKAGE.matches(step.packageName)) null else "应用包名不对：${step.packageName}"
            }

            is SkillStep.Tap -> {
                if (step.ocr && step.target.text.isNullOrBlank()) "截图找字点击需要写要找的文字" else selectorProblem(step.target)
            }

            is SkillStep.Wait -> {
                selectorProblem(step.target)
            }

            is SkillStep.Type -> {
                step.target?.let(::selectorProblem) ?: textProblem(step.text, allowEmpty = true)
            }

            is SkillStep.Verify -> {
                textProblem(step.text, allowEmpty = false)
            }

            is SkillStep.Scroll, SkillStep.Back, is SkillStep.HandOver -> {
                null
            }

            is SkillStep.Image -> {
                imageProblem(step)
            }

            is SkillStep.Collect -> {
                collectProblem(step)
            }
        }

    private fun imageProblem(step: SkillStep.Image): String? {
        val expected = step.templateWidth * step.templateHeight
        val bytes =
            runCatching {
                java.util.Base64
                    .getDecoder()
                    .decode(step.gray)
                    .size
            }.getOrDefault(-1)
        return when {
            step.templateWidth !in 1..ImageMatcher.MAX_SIDE || step.templateHeight !in 1..ImageMatcher.MAX_SIDE -> "图片模板尺寸不对"
            bytes != expected -> "图片模板数据不完整"
            step.width < 1 || step.height < 1 -> "图片区域大小不对"
            else -> null
        }
    }

    private fun collectProblem(step: SkillStep.Collect): String? =
        when {
            step.maxItems !in 1..MAX_COLLECT_ITEMS -> "收集条数要在 1–$MAX_COLLECT_ITEMS 之间"
            step.maxScrolls !in 1..MAX_COLLECT_SCROLLS -> "滑动次数要在 1–$MAX_COLLECT_SCROLLS 之间"
            step.pauseMs !in MIN_PAUSE_MS..MAX_PAUSE_MS -> "每次滑动的间隔要在 $MIN_PAUSE_MS–$MAX_PAUSE_MS 毫秒之间"
            else -> null
        }

    private fun selectorProblem(s: NodeSelector): String? =
        if (s.isEmpty) "没有说明要点哪个控件（文字、资源 id 或类名至少一个）" else textProblem(s.text.orEmpty(), allowEmpty = true)

    private fun textProblem(
        text: String,
        allowEmpty: Boolean,
    ): String? =
        when {
            !allowEmpty && text.isBlank() -> "核对的文字不能为空"
            text.length > MAX_TEXT -> "文字太长（最多 $MAX_TEXT 字）"
            else -> null
        }
}
