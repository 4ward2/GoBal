package com.danielealbano.androidremotecontrolmcp.ui.chat

import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBubble
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpChatLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@DisplayName("ChatPresentation")
class ChatPresentationTest {
    @Test
    fun `phone-control acknowledgement becomes a task card titled with the user request`() {
        val messages =
            listOf(
                ChatBubble("user", "打开相机拍张照", id = "u1"),
                ChatBubble("assistant", OLD_ACK, id = "a1"),
            )
        assertEquals(
            listOf(
                ChatItem.UserMessage("u1", "打开相机拍张照"),
                ChatItem.PhoneTask("a1", "打开相机拍张照", "452f102a"),
            ),
            ChatPresentation.toItems(messages),
        )
    }

    @Test
    fun `a message standing for a task becomes its card and its text never shows an id`() {
        val task = PhoneMcpChatLogic.taskBubble("t42").copy(id = "a1")
        val messages = listOf(ChatBubble("user", "点一杯奶茶", id = "u1"), task)
        assertEquals(ChatItem.PhoneTask("a1", "点一杯奶茶", "t42"), ChatPresentation.toItems(messages).last())
        assertFalse(task.content.contains("t42"))
        val helper = PhoneMcpChatLogic.taskBubble(PhoneMcpChatLogic.wakeTaskId("452f102a"))
        assertFalse(helper.content.contains("452f102a"))
        assertFalse(helper.content.contains("wake"))
    }

    @Test
    fun `ordinary assistant text stays a message`() {
        val items = ChatPresentation.toItems(listOf(ChatBubble("assistant", "你好", id = "a1")))
        assertEquals(listOf(ChatItem.AssistantMessage("a1", "你好")), items)
    }

    @Test
    fun `a user message quoting the acknowledgement is not turned into a task`() {
        val text = OLD_ACK
        val items = ChatPresentation.toItems(listOf(ChatBubble("user", text, "u1")))
        assertEquals(listOf(ChatItem.UserMessage("u1", text)), items)
    }

    @Test
    fun `bubbles without ids still get stable distinct keys`() {
        val items = ChatPresentation.toItems(listOf(ChatBubble("user", "a"), ChatBubble("user", "a")))
        assertEquals(2, items.map { it.key }.toSet().size)
    }

    @Test
    fun `failure replies are not task cards`() {
        assertNull(ChatPresentation.wakeAckTaskId("无法启动机上操控：超时"))
    }

    private companion object {
        /** How a task was recorded before messages had a task field. */
        const val OLD_ACK = "已交给机上 PhoneMCP agent 执行手机操作（wake_id=452f102a）。请看手机界面；若要问答请说「怎么打开…」。"
    }

    @ParameterizedTest
    @CsvSource("6,早上好", "12,中午好", "15,下午好", "20,晚上好", "2,晚上好")
    fun `greeting follows the time of day`(
        hour: Int,
        expected: String,
    ) {
        assertEquals(expected, ChatPresentation.greeting(hour))
    }
}
