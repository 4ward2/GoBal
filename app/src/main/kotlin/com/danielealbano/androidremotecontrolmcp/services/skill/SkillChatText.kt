package com.danielealbano.androidremotecontrolmcp.services.skill

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A reply of the agent split into what the user reads and the skill it proposes (if any). */
data class AgentEdit(
    val text: String,
    val skill: Skill?,
)

/**
 * What the chat tells the agent about recordings without showing it (hidden notices), and how the
 * agent's changes to a recorded skill come back: a ```json block with the whole skill, taken out
 * of the reply before it is shown. Pure, so the protocol is tested apart from the model.
 */
object SkillChatText {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    private val BLANK_LINES = Regex("\n[ \t]*(\n[ \t]*)+\n")
    private val JSON_BLOCK = Regex("```(?:json)?\\s*(\\{[\\s\\S]*\\})\\s*```")

    const val AI_RECORDING_STARTED =
        "【系统通知，不要复述这句话】用户点了「AI 录制」：接下来他会用语音说出要手机做的事，手机上的 agent 会照做一遍，" +
            "做成后自动存成技能，下次同样的话不用 AI 就能重放。请用一句话告诉他可以开始说了。"

    const val MANUAL_RECORDING_STARTED =
        "【系统通知，不要复述这句话】用户开始「人工录制」：他会自己在手机上操作一遍。屏幕上有个悬浮球，点开有暂停、停止，" +
            "还有眼镜可以截图识别文字或图片（选一个会帮他点，多选则作为核对项）。录完点停止会回到这里。请用一句话告诉他开始吧。"

    fun steps(skill: Skill): String {
        val lines = skill.steps.mapIndexed { i, s -> "${i + 1}. ${SkillText.describe(s)}" }
        return lines.joinToString("\n")
    }

    fun encode(skill: Skill): String =
        json.encodeToString(
            Skill.serializer(),
            skill.copy(createdAt = 0, updatedAt = 0, runCount = 0, lastRunOk = null),
        )

    /** Shown in the chat (and read by the model as context) for a recorded skill's card. */
    fun card(skill: Skill): String = "【录好的操作】${skill.name}\n${steps(skill)}"

    fun recorded(skill: Skill): String =
        "【系统通知，不要复述这句话】用户刚手动录好了一段手机操作，已存成技能「${skill.name}」，共 ${skill.steps.size} 步：\n" +
            steps(skill) + "\n技能 JSON：" + encode(skill) + "\n" +
            "请：1）用通俗的话逐步说明每一步在做什么，指出可能多余或容易失败的步骤；2）建议一个好记的名字，指出哪些词适合设为参数" +
            "（名字里用 {{参数名}}）；3）提醒他可以长按拖动步骤卡片调整顺序、点选某一步给你提修改意见。" +
            "用户同意改名或修改之前，不要附 JSON。"

    /** Context for a message sent while a recorded skill is being reviewed. */
    fun review(
        skill: Skill,
        focus: Int?,
    ): String {
        val target =
            focus?.let { i -> skill.steps.getOrNull(i)?.let { "\n用户这句话针对的是第 ${i + 1} 步：${SkillText.describe(it)}" } }
        return "【系统说明，不要复述】用户正在和你一起整理录好的技能「${skill.name}」，当前步骤：\n" + steps(skill) +
            "\n技能 JSON：" + encode(skill) + target.orEmpty() + "\n" +
            "请用中文简短回答上一条用户消息。如果需要改动技能（调整顺序、删除或合并步骤、修改某一步、改名、把某个词设为参数），" +
            "在回复最后附上修改后的完整技能 JSON，放在 ```json 代码块里，字段格式与上面相同；不需要改动就不要附 JSON。"
    }

    fun played(result: SkillRunResult): String {
        val log = result.log.joinToString("\n")
        val collected =
            if (result.collected.isEmpty()) {
                ""
            } else {
                "\n收集到 ${result.collected.size} 条" +
                    (result.savedTo?.let { "，存在 $it" } ?: "")
            }
        return "【系统通知，不要复述这句话】用户刚播放了技能「${result.skillName}」，结果：${result.status.name.lowercase()}，" +
            "${result.message}\n$log$collected\n请用一两句话告诉他结果；没做完的话说明卡在哪一步、可能的原因。"
    }

    /**
     * Splits the agent's reply. A ```json block holding a skill (or only the fields to change) is
     * applied over [current]; a block that is not a valid skill is left in the text.
     */
    fun parse(
        reply: String,
        current: Skill,
    ): AgentEdit {
        val block = JSON_BLOCK.findAll(reply).lastOrNull()
        val skill = block?.let { merged(it.groupValues[1], current) }
        if (block == null || skill == null) return AgentEdit(reply.trim(), null)
        // The block leaves a hole of blank lines behind; one empty line between paragraphs is enough.
        val text =
            reply
                .removeRange(block.range)
                .replace(BLANK_LINES, "\n\n")
                .trim()
                .ifEmpty { "好的，已经改好了。" }
        return AgentEdit(text, skill)
    }

    /** [changes] laid over [current]; null unless the result is a valid skill. Run history is kept. */
    private fun merged(
        changes: String,
        current: Skill,
    ): Skill? =
        runCatching {
            val base = json.parseToJsonElement(encode(current)).jsonObject
            val edits = json.parseToJsonElement(changes).jsonObject
            json.decodeFromJsonElement(Skill.serializer(), JsonObject(base + edits))
        }.getOrNull()
            ?.takeIf { SkillValidator.problems(it).isEmpty() }
            ?.copy(source = current.source, runCount = current.runCount, lastRunOk = current.lastRunOk)

    /** The step at [from] moved to [to]. */
    fun move(
        skill: Skill,
        from: Int,
        to: Int,
    ): Skill {
        if (from !in skill.steps.indices || to !in skill.steps.indices || from == to) return skill
        val steps = skill.steps.toMutableList()
        steps.add(to, steps.removeAt(from))
        return skill.copy(steps = steps)
    }
}
