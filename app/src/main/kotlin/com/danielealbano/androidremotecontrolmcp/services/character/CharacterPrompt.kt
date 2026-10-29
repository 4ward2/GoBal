package com.danielealbano.androidremotecontrolmcp.services.character

import com.danielealbano.androidremotecontrolmcp.data.model.AssistantProfile

/**
 * Turns a [CharacterCard] into what the language model sees. Cards use SillyTavern's macros
 * (`{{char}}`, `{{user}}`, and the older `<BOT>` / `<USER>`), which are filled in here.
 *
 * Phone control mostly never reaches this prompt: the local rules hand those requests to the agent
 * first. What they miss, the model hands over with `<phone_task>` (see `PhoneHandoff`), so every
 * prompt ends with the same rule.
 */
object CharacterPrompt {
    const val DEFAULT_USER = "用户"

    // Both braces are escaped: Android's ICU regex rejects a bare closing brace that the JVM accepts.
    private val MACROS = Regex("\\{\\{\\s*(char|user)\\s*\\}\\}|<(BOT|USER)>", RegexOption.IGNORE_CASE)

    private const val PHONE_RULE =
        "你自己不能操作手机，也绝不能说「已打开」「正在为你打开」之类假装执行了操作的话。" +
            "如果用户是想让手机去做一件事（打开应用，在某个应用里查看、搜索、点选、填写、比价等），" +
            "不要用文字回答，整条回复只写一行：<phone_task>用一句话复述要手机做的事</phone_task>。" +
            "系统会接手去做，并把过程显示给用户。只是问怎么用、问知识或聊天时，正常回答。"

    /**
     * The plain assistant, when no character is active.
     *
     * @param memory the enabled memory files' prompt section (see `MemoryRepository.context`), or blank.
     */
    fun assistantSystem(
        assistantName: String,
        profile: AssistantProfile = AssistantProfile(),
        memory: String = "",
    ): String {
        val sections =
            listOfNotNull(
                "你是 $assistantName，手机上的助手。简洁中文回答。",
                profile.description.takeIf { it.isNotBlank() }?.let { "【关于你】\n$it" },
                profile.backstory.takeIf { it.isNotBlank() }?.let { "【背景故事】\n$it" },
                memory.takeIf { it.isNotBlank() },
                PHONE_RULE,
            )
        return sections.joinToString("\n\n")
    }

    /** Everything the card says about who the model is, the user's edits, the memory files, then the phone rule. */
    fun system(
        card: CharacterCard,
        userName: String = DEFAULT_USER,
        memory: String = "",
    ): String {
        val lead =
            card.systemPrompt.ifBlank {
                "你正在扮演「${card.name}」，和 $userName 对话。始终保持这个角色的口吻、性格和设定，" +
                    "用第一人称说话，不要跳出角色解释自己是 AI。用中文回答，除非对方用别的语言。"
            }
        val sections =
            listOfNotNull(
                lead,
                card.description.takeIf { it.isNotBlank() }?.let { "【角色描述】\n$it" },
                card.personality.takeIf { it.isNotBlank() }?.let { "【性格】\n$it" },
                card.backstory.takeIf { it.isNotBlank() }?.let { "【背景故事】\n$it" },
                card.scenario.takeIf { it.isNotBlank() }?.let { "【场景】\n$it" },
                card.exampleMessages.takeIf { it.isNotBlank() }?.let { "【对话示例】\n$it" },
                card.postHistoryInstructions.takeIf { it.isNotBlank() },
                memory.takeIf { it.isNotBlank() },
                statusRule(card.status),
                PHONE_RULE,
            )
        return fill(sections.joinToString("\n\n"), card.name, userName)
    }

    /**
     * The character keeps a short status shown under its name, like a messenger's mood line, and
     * changes it itself with a tag at the end of a reply. [StatusTag] strips the tag before display.
     */
    private fun statusRule(current: String): String =
        "【状态】你的名字下方会显示一条 2–6 个字的状态，像「生闷气中」「在晒太阳」「等你回来」。" +
            "心情或正在做的事变了，就在回复的最后一行单独写 <status>新状态</status>；没变就什么都不写。" +
            "当前状态：${current.ifBlank { "在线" }}。"

    /** The card's opening line, ready to show as the character's first bubble; null when it has none. */
    fun greeting(
        card: CharacterCard,
        userName: String = DEFAULT_USER,
    ): String? = card.firstMessage.takeIf { it.isNotBlank() }?.let { fill(it, card.name, userName) }

    fun fill(
        text: String,
        charName: String,
        userName: String,
    ): String =
        MACROS.replace(text) { match ->
            val key = (match.groupValues[1].ifEmpty { match.groupValues[2] }).lowercase()
            if (key == "char" || key == "bot") charName else userName
        }
}

/** The `<status>…</status>` tag a character appends to a reply to change its status line. */
object StatusTag {
    private const val MAX_LENGTH = 12
    private val TAG = Regex("<status>\\s*([^<]{1,40}?)\\s*</status>", RegexOption.IGNORE_CASE)

    data class Split(
        val text: String,
        val status: String?,
    )

    /** The reply without the tag, and the new status when one was given (last one wins). */
    fun split(reply: String): Split {
        val status =
            TAG
                .findAll(reply)
                .lastOrNull()
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.take(MAX_LENGTH)
        val text = TAG.replace(reply, "").trim()
        return Split(text, status?.takeIf { it.isNotEmpty() })
    }
}
