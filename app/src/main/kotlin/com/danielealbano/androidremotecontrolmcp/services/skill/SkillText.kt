package com.danielealbano.androidremotecontrolmcp.services.skill

/** One-line Chinese descriptions of steps, for the editor, run logs and tool results. */
object SkillText {
    /** [appName]: the app's name for a package, when the caller can look it up (shown instead of the package). */
    fun describe(
        step: SkillStep,
        appName: (String) -> String? = { null },
    ): String =
        when (step) {
            is SkillStep.Launch -> {
                describeLaunch(step, appName(step.packageName))
            }

            is SkillStep.Tap -> {
                describeTap(step)
            }

            is SkillStep.Image -> {
                val what = step.label.ifBlank { "这张图" }
                if (step.tap) "点图片「$what」" else "核对屏幕上有图片「$what」"
            }

            is SkillStep.Collect -> {
                "往下滑到底，收集清单（最多 ${step.maxItems} 条）"
            }

            is SkillStep.Type -> {
                if (step.target == null) "输入「${step.text}」" else "在「${describe(step.target)}」输入「${step.text}」"
            }

            is SkillStep.Verify -> {
                "核对屏幕上有「${step.text}」" + if (step.ocr) "（必要时识别截图）" else ""
            }

            is SkillStep.Wait -> {
                "等「${describe(step.target)}」出现"
            }

            is SkillStep.Scroll -> {
                if (step.direction == SkillScrollDirection.DOWN) "往下滑" else "往上滑"
            }

            SkillStep.Back -> {
                "返回"
            }

            is SkillStep.HandOver -> {
                "交给你" + if (step.reason.isBlank()) "" else "：${step.reason}"
            }
        }

    private fun describeLaunch(
        step: SkillStep.Launch,
        name: String?,
    ): String = if (step.uri.isNullOrBlank()) "打开「${name ?: step.packageName}」" else "打开链接 ${step.uri}"

    private fun describeTap(step: SkillStep.Tap): String =
        when {
            step.ocr -> "点「${describe(step.target)}」（识别截图找字）"
            step.optional -> "点「${describe(step.target)}」（出现了才点）"
            else -> "点「${describe(step.target)}」"
        }

    fun describe(selector: NodeSelector): String =
        selector.text?.takeIf { it.isNotBlank() }
            ?: selector.resourceId?.substringAfter(":id/")?.takeIf { it.isNotBlank() }
            ?: selector.className?.substringAfterLast('.')
            ?: "?"

    /** The literal texts of a step that could become a parameter (typed text, a tapped label). */
    fun literals(step: SkillStep): List<String> =
        when (step) {
            is SkillStep.Tap -> listOfNotNull(step.target.text)
            is SkillStep.Type -> listOf(step.text)
            is SkillStep.Verify -> listOf(step.text)
            else -> emptyList()
        }.filter { it.isNotBlank() && SkillParams.namesIn(it).isEmpty() }
}
