package com.danielealbano.androidremotecontrolmcp.services.skill

/** `{{name}}` placeholders: finding, filling and introducing them. */
object SkillParams {
    private val PLACEHOLDER = Regex("\\{\\{\\s*([^\\{\\}]+?)\\s*\\}\\}")

    /** A whole `{{…}}` token, for splitting a pattern around its slots. */
    val ANY_PLACEHOLDER = Regex("\\{\\{[^\\{\\}]+\\}\\}")

    fun placeholder(name: String): String = "{{$name}}"

    fun namesIn(text: String?): List<String> =
        text?.let { t ->
            PLACEHOLDER.findAll(t).map { it.groupValues[1] }.toList()
        } ?: emptyList()

    /** Every placeholder the skill uses, in order of first appearance. */
    fun namesUsed(skill: Skill): List<String> =
        (namesIn(skill.name) + skill.steps.flatMap { step -> texts(step).flatMap { namesIn(it) } }).distinct()

    /**
     * The value for every placeholder: [given] first, then the declared default. Fails naming the
     * placeholders that have neither, and the given names the skill does not have.
     */
    fun resolve(
        skill: Skill,
        given: Map<String, String>,
    ): Result<Map<String, String>> {
        val used = namesUsed(skill)
        val defaults = skill.params.associate { it.name to it.defaultValue }
        val unknown = given.keys.filterNot { it in used || it in defaults }
        val values =
            used.associateWith { name -> given[name]?.takeIf { it.isNotBlank() } ?: defaults[name].orEmpty() }
        val missing = values.filterValues { it.isBlank() }.keys
        return when {
            unknown.isNotEmpty() -> {
                Result.failure(IllegalArgumentException("技能「${skill.name}」没有参数：${unknown.joinToString("、")}"))
            }

            missing.isNotEmpty() -> {
                Result.failure(IllegalArgumentException("缺少参数：${missing.joinToString("、")}"))
            }

            else -> {
                Result.success(values)
            }
        }
    }

    fun fill(
        text: String,
        values: Map<String, String>,
    ): String = PLACEHOLDER.replace(text) { m -> values[m.groupValues[1]] ?: m.value }

    fun fill(
        step: SkillStep,
        values: Map<String, String>,
    ): SkillStep =
        when (step) {
            is SkillStep.Launch -> {
                step.copy(uri = step.uri?.let { fill(it, values) })
            }

            is SkillStep.Tap -> {
                step.copy(target = fill(step.target, values))
            }

            is SkillStep.Type -> {
                step.copy(target = step.target?.let { fill(it, values) }, text = fill(step.text, values))
            }

            is SkillStep.Verify -> {
                step.copy(text = fill(step.text, values))
            }

            is SkillStep.Wait -> {
                step.copy(target = fill(step.target, values))
            }

            is SkillStep.Scroll, SkillStep.Back, is SkillStep.HandOver, is SkillStep.Collect, is SkillStep.Image -> {
                step
            }
        }

    /**
     * Turns every occurrence of [literal] in the skill (name and steps) into `{{param}}` and
     * declares the parameter with [literal] as its default. Used when a recorded "蜜雪冰城" should
     * become "{{店名}}".
     */
    fun parameterize(
        skill: Skill,
        param: String,
        literal: String,
    ): Skill {
        require(param.isNotBlank() && '{' !in param && '}' !in param) { "参数名不能为空，也不能有花括号" }
        require(literal.isNotBlank()) { "要替换的文字不能为空" }
        val token = placeholder(param.trim())

        fun sub(text: String) = text.replace(literal, token)

        // A parameter names a thing ("蜜雪冰城"); the recorded control said more ("蜜雪冰城(高新店) 4.8分"),
        // which only fits that one shop. The selector keeps the name alone and matches by containment.
        fun subSel(s: NodeSelector): NodeSelector =
            if (s.text?.contains(literal) == true) s.copy(text = token, match = TextMatch.CONTAINS) else s
        val steps =
            skill.steps.map { step ->
                when (step) {
                    is SkillStep.Launch -> step.copy(uri = step.uri?.let(::sub))
                    is SkillStep.Tap -> step.copy(target = subSel(step.target))
                    is SkillStep.Type -> step.copy(target = step.target?.let(::subSel), text = sub(step.text))
                    is SkillStep.Verify -> step.copy(text = sub(step.text))
                    is SkillStep.Wait -> step.copy(target = subSel(step.target))
                    is SkillStep.Scroll, SkillStep.Back, is SkillStep.HandOver, is SkillStep.Collect, is SkillStep.Image -> step
                }
            }
        val params = skill.params.filterNot { it.name == param.trim() } + SkillParam(param.trim(), literal)
        return skill.copy(name = sub(skill.name), steps = steps, params = params)
    }

    /**
     * After a tap on a control named by a parameter (the shop, the dish), the next screen must
     * show that name. The order is placed only once that check passes, whatever layout the app
     * chose this time.
     */
    fun withCheckpoints(skill: Skill): Skill {
        val out = mutableListOf<SkillStep>()
        skill.steps.forEachIndexed { i, step ->
            out += step
            val text = (step as? SkillStep.Tap)?.target?.text
            val next = skill.steps.getOrNull(i + 1)
            val alreadyChecked = next is SkillStep.Verify && next.text == text
            if (text != null && namesIn(text).isNotEmpty() && !alreadyChecked) {
                out += SkillStep.Verify(text)
            }
        }
        return skill.copy(steps = out)
    }

    private fun fill(
        s: NodeSelector,
        values: Map<String, String>,
    ) = s.copy(text = s.text?.let { fill(it, values) })

    private fun texts(step: SkillStep): List<String?> =
        when (step) {
            is SkillStep.Launch -> listOf(step.uri)
            is SkillStep.Tap -> listOf(step.target.text)
            is SkillStep.Type -> listOf(step.target?.text, step.text)
            is SkillStep.Verify -> listOf(step.text)
            is SkillStep.Wait -> listOf(step.target.text)
            is SkillStep.Scroll, SkillStep.Back, is SkillStep.HandOver, is SkillStep.Collect, is SkillStep.Image -> emptyList()
        }
}
