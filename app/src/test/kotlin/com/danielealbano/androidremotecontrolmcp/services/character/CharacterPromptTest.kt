package com.danielealbano.androidremotecontrolmcp.services.character

import com.danielealbano.androidremotecontrolmcp.data.model.AssistantProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CharacterPrompt")
class CharacterPromptTest {
    private val card =
        CharacterCard(
            id = "abc",
            name = "小夏",
            description = "一只爱说话的猫，主人是{{user}}。",
            personality = "傲娇",
            scenario = "在阳台上",
            firstMessage = "{{user}}，你回来啦～我是{{char}}。",
            exampleMessages = "<START>\n<USER>: 你好\n<BOT>: 喵",
        )

    @Test
    fun `macros are filled in every spelling`() {
        assertEquals("用户，你回来啦～我是小夏。", CharacterPrompt.greeting(card))
        assertEquals("老王，你回来啦～我是小夏。", CharacterPrompt.greeting(card, userName = "老王"))
        assertEquals("小夏 用户 小夏 用户", CharacterPrompt.fill("{{ Char }} {{USER}} <BOT> <USER>", "小夏", "用户"))
    }

    @Test
    fun `system prompt has the card sections and the phone rule`() {
        val system = CharacterPrompt.system(card)
        assertTrue(system.startsWith("你正在扮演「小夏」"))
        assertTrue(system.contains("【角色描述】\n一只爱说话的猫，主人是用户。"))
        assertTrue(system.contains("【性格】\n傲娇"))
        assertTrue(system.contains("【场景】\n在阳台上"))
        assertTrue(system.contains("小夏: 喵"))
        assertTrue(system.contains("不能操作手机"))
        assertFalse(system.contains("{{"))
    }

    @Test
    fun `a card's own system prompt replaces the default lead and empty sections are skipped`() {
        val own = card.copy(systemPrompt = "You are {{char}}.", personality = "", scenario = "", exampleMessages = "")
        val system = CharacterPrompt.system(own)
        assertTrue(system.startsWith("You are 小夏."))
        assertFalse(system.contains("你正在扮演"))
        assertFalse(system.contains("【性格】"))
        assertFalse(system.contains("【场景】"))
    }

    @Test
    fun `no first message means no greeting`() {
        assertNull(CharacterPrompt.greeting(card.copy(firstMessage = "  ")))
    }

    @Test
    fun `the plain assistant prompt uses its name and keeps the phone rule`() {
        val system = CharacterPrompt.assistantSystem("小助手")
        assertTrue(system.startsWith("你是 小助手"))
        assertTrue(system.contains("不能操作手机"))
    }

    @Test
    fun `backstory and memory files are placed in the prompt`() {
        val system = CharacterPrompt.system(card.copy(backstory = "小时候在乡下长大"), memory = "【记忆资料】\n《日记》\n今天下雨")
        assertTrue(system.indexOf("【背景故事】\n小时候在乡下长大") > system.indexOf("【性格】"))
        assertTrue(system.indexOf("【记忆资料】") < system.indexOf("【状态】"))
        val assistant = CharacterPrompt.assistantSystem("小助手", AssistantProfile("耐心", "老家在北方"), "【记忆资料】\n《x》\ny")
        assertTrue(assistant.contains("【关于你】\n耐心"))
        assertTrue(assistant.contains("【背景故事】\n老家在北方"))
        assertTrue(assistant.contains("《x》"))
        assertTrue(assistant.endsWith("正常回答。"))
        assertTrue(assistant.contains("<phone_task>"))
    }

    @Test
    fun `system prompt tells the character how to change its status`() {
        assertTrue(CharacterPrompt.system(card).contains("当前状态：在线"))
        assertTrue(CharacterPrompt.system(card.copy(status = "生闷气中")).contains("当前状态：生闷气中"))
        assertTrue(CharacterPrompt.system(card).contains("<status>新状态</status>"))
        assertFalse(CharacterPrompt.assistantSystem("小助手").contains("<status>"))
    }

    @Test
    fun `status tag is stripped from the reply and the last one wins`() {
        val split = StatusTag.split("哼，不理你了。\n<status>生闷气中</status>")
        assertEquals("哼，不理你了。", split.text)
        assertEquals("生闷气中", split.status)

        val twice = StatusTag.split("<STATUS> 在晒太阳 </STATUS>好吧<status>睡着了</status>")
        assertEquals("好吧", twice.text)
        assertEquals("睡着了", twice.status)
    }

    @Test
    fun `replies without a tag are returned as they are`() {
        val split = StatusTag.split("  今天天气不错。 ")
        assertEquals("今天天气不错。", split.text)
        assertNull(split.status)
        assertNull(StatusTag.split("<status></status>喵").status)
    }

    @Test
    fun `status is cut to a short line`() {
        val split = StatusTag.split("<status>一二三四五六七八九十一二三四五</status>")
        assertEquals("一二三四五六七八九十一二", split.status)
    }
}
