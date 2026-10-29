package com.danielealbano.androidremotecontrolmcp.services.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneMcpChatLogicTest {
    @Test
    fun formatChatError_503IsBusyNotMissingKey() {
        val msg =
            PhoneMcpChatLogic.formatChatError(
                "HTTP 503 engine overloaded code 10110",
            )
        assertTrue(msg.contains("繁忙") || msg.contains("503"))
        assertFalse(msg.contains("未配置"))
        assertFalse(msg.contains("LongCat"))
    }

    @Test
    fun formatChatError_providerHttpErrorIsShownAsIs() {
        val msg = PhoneMcpChatLogic.formatChatError("HTTP 401: invalid api key")
        assertEquals("服务商出错：HTTP 401: invalid api key", msg)
        assertFalse(msg.contains("provider.json"))
    }

    @Test
    fun formatChatError_suTimeoutIsRetryable() {
        val msg = PhoneMcpChatLogic.formatChatError("su timeout")
        assertTrue(msg.contains("超时"))
    }

    @Test
    fun formatChatError_wakeSuTimeoutIsControlSpecific() {
        val msg =
            PhoneMcpChatLogic.formatChatError(
                "wake su timeout: Magisk su 或 local_wake.sh 卡住（常见于 am force-stop）",
            )
        assertTrue(msg.contains("机上操控"))
        assertFalse(msg.contains("对话超时"))
    }

    @Test
    fun formatChatError_nanoHttpdPoisonedKeepAlive() {
        val msg =
            PhoneMcpChatLogic.formatChatError(
                "BAD REQUEST: Syntax error. HTTP verb {}POST unhandled.",
            )
        assertTrue(msg.contains("本地对话服务") || msg.contains("重试"))
        assertFalse(msg.contains("{}POST"))
    }

    @Test
    fun parseChatResponse_okContent() {
        val r = PhoneMcpChatLogic.parseChatResponse("""{"ok":true,"content":"你好"}""")
        assertTrue(r.ok)
        assertEquals("你好", r.content)
    }

    @Test
    fun parseChatResponse_errorField() {
        val r = PhoneMcpChatLogic.parseChatResponse("""{"ok":false,"error":"engine overloaded"}""")
        assertFalse(r.ok)
        assertTrue(PhoneMcpChatLogic.formatChatError(r.error).contains("繁忙"))
    }

    @Test
    fun summarizeProvider_stripsSecrets() {
        val raw =
            """
            {"provider":"anthropic_compat","model":"astron-code-latest",
             "base_url":"https://example.xf-yun.com/anthropic","api_key":"sk-secret",
             "auth_env":"ANTHROPIC_AUTH_TOKEN"}
            """.trimIndent()
        val s = PhoneMcpChatLogic.summarizeProvider(raw)
        assertTrue(s.configured)
        assertEquals("anthropic_compat", s.kind)
        assertEquals("astron-code-latest", s.model)
        assertTrue(s.endpoint.contains("xf-yun"))
        assertFalse(s.endpoint.contains("sk-secret"))
        assertTrue(s.authEnv.contains("ANTHROPIC"))
    }

    @Test
    fun summarizeProvider_blankIsNotConfigured() {
        val s = PhoneMcpChatLogic.summarizeProvider("")
        assertFalse(s.configured)
    }

    @Test
    fun buildVoskWsUrl_putsNoWakeFirst() {
        val url = PhoneMcpChatLogic.buildVoskWsUrl("ws://127.0.0.1:8888/client/ws/speech")
        assertTrue(url.startsWith("ws://127.0.0.1:8888/client/ws/speech?"))
        assertTrue(url.contains("no_wake=1"))
        assertTrue(url.contains("partial=true"))
        assertTrue(url.contains("rate=(int)16000"))
    }

    @Test
    fun buildVoskWsUrl_addsLanguageOnlyWhenGiven() {
        val base = "ws://127.0.0.1:8888/client/ws/speech"
        assertFalse(PhoneMcpChatLogic.buildVoskWsUrl(base).contains("lang="))
        assertTrue(PhoneMcpChatLogic.buildVoskWsUrl(base, "en-in").endsWith("&lang=en-in"))
    }

    @Test
    fun buildVoskWsUrl_rejectsInjectedLanguage() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            PhoneMcpChatLogic.buildVoskWsUrl("ws://127.0.0.1:8888/client/ws/speech", "hi&no_wake=0")
        }
    }

    @Test
    fun parseVoskError_returnsServerMessage() {
        assertEquals(
            "手机上没有装「hi」语音模型",
            PhoneMcpChatLogic.parseVoskError("""{"status":9,"message":"手机上没有装「hi」语音模型","id":"x"}"""),
        )
        assertEquals(
            null,
            PhoneMcpChatLogic.parseVoskError("""{"status":0,"result":{"hypotheses":[{"transcript":"a"}],"final":true}}"""),
        )
    }

    @Test
    fun parseVoskMessage_finalTranscript() {
        val h =
            PhoneMcpChatLogic.parseVoskMessage(
                """{"status":0,"result":{"hypotheses":[{"transcript":"打开设置"}],"final":true}}""",
            )
        assertEquals("打开设置", h?.transcript)
        assertEquals(true, h?.isFinal)
    }

    @Test
    fun parseVoskMessage_partial() {
        val h =
            PhoneMcpChatLogic.parseVoskMessage(
                """{"result":{"hypotheses":[{"transcript":"打开"}],"final":false}}""",
            )
        assertEquals("打开", h?.transcript)
        assertEquals(false, h?.isFinal)
    }

    @Test
    fun parseStatus_readsProviderAndStt() {
        val s =
            PhoneMcpChatLogic.parseStatus(
                """{"ok":true,"stt_listening":true,"agent_watch":true,"agent_runner":false,
                   "provider":{"configured":true,"kind":"anthropic_compat","model":"astron",
                   "endpoint":"https://x","auth_env":"ANTHROPIC_AUTH_TOKEN"}}""",
            )
        assertTrue(s.httpOk)
        assertTrue(s.sttListening)
        assertTrue(s.agentWatch)
        assertFalse(s.agentRunner)
        assertEquals("anthropic_compat", s.provider.kind)
        assertEquals("astron", s.provider.model)
    }

    @Test
    fun chatRequestJson_includesMessagesAndTimeout() {
        val body =
            PhoneMcpChatLogic.chatRequestJson(
                messages =
                    listOf(
                        "user" to "hi",
                        "assistant" to "hello",
                    ),
                timeoutS = 45,
            )
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"content\":\"hi\""))
        assertTrue(body.contains("\"timeout_s\":45"))
    }

    @Test
    fun lazyColumnKey_duplicateContentIsUnique() {
        val a = ChatBubble(role = "user", content = "hello")
        val b = ChatBubble(role = "user", content = "hello")
        assertTrue(PhoneMcpChatLogic.lazyColumnKey(0, a) != PhoneMcpChatLogic.lazyColumnKey(1, b))
    }

    @Test
    fun ensureBubbleIds_duplicateContentGetsDistinctIds() {
        val out =
            PhoneMcpChatLogic.ensureBubbleIds(
                listOf(
                    ChatBubble(role = "user", content = "hello"),
                    ChatBubble(role = "user", content = "hello"),
                ),
            )
        assertEquals(2, out.map { it.id }.toSet().size)
        assertTrue(out.all { it.id.isNotBlank() })
    }

    @Test
    fun isPhoneControlIntent_openAppIsControl() {
        assertTrue(PhoneMcpChatLogic.isPhoneControlIntent("打开相机"))
        assertTrue(PhoneMcpChatLogic.isPhoneControlIntent("打开设置里的蓝牙"))
        assertTrue(PhoneMcpChatLogic.isPhoneControlIntent("回到桌面"))
    }

    @Test
    fun isPhoneControlIntent_politeLeadInIsStillControl() {
        assertTrue(PhoneMcpChatLogic.isPhoneControlIntent("帮我打开美团"))
        assertTrue(PhoneMcpChatLogic.isPhoneControlIntent("请打开淘宝"))
        assertTrue(PhoneMcpChatLogic.isPhoneControlIntent("麻烦帮我打开肯德基"))
        assertTrue(PhoneMcpChatLogic.isPhoneControlIntent("我想打开闲鱼"))
        assertTrue(PhoneMcpChatLogic.isPhoneControlIntent("你帮我启动麦当劳"))
        assertTrue(PhoneMcpChatLogic.isPhoneControlIntent("帮我在美团点一份外卖"))
        assertTrue(PhoneMcpChatLogic.isPhoneControlIntent("用淘宝搜一下手机支架"))
    }

    @Test
    fun isPhoneControlIntent_ordinaryRequestsStayChat() {
        assertFalse(PhoneMcpChatLogic.isPhoneControlIntent("帮我写一首诗"))
        assertFalse(PhoneMcpChatLogic.isPhoneControlIntent("美团是什么"))
        assertFalse(PhoneMcpChatLogic.isPhoneControlIntent("你好"))
        assertFalse(PhoneMcpChatLogic.isPhoneControlIntent("帮我看看这句话什么意思"))
        assertFalse(PhoneMcpChatLogic.isPhoneControlIntent("请问怎么打开美团"))
    }

    @Test
    fun isPhoneControlIntent_howToIsQuestion() {
        assertFalse(PhoneMcpChatLogic.isPhoneControlIntent("怎么打开相机"))
        assertFalse(PhoneMcpChatLogic.isPhoneControlIntent("如何开启蓝牙"))
    }

    @Test
    fun parseWakeResponse_readsWakeId() {
        val w =
            PhoneMcpChatLogic.parseWakeResponse(
                """{"ok":true,"wake_id":"abc-123","message":"launched"}""",
            )
        assertTrue(w.ok)
        assertEquals("abc-123", w.wakeId)
    }

    @Test
    fun canSendDraft_requiresNonBlankIdleComposer() {
        assertTrue(PhoneMcpChatLogic.canSendDraft("hello", sending = false, listening = false))
        assertFalse(PhoneMcpChatLogic.canSendDraft("   ", sending = false, listening = false))
        assertFalse(PhoneMcpChatLogic.canSendDraft("hello", sending = true, listening = false))
        assertFalse(PhoneMcpChatLogic.canSendDraft("hello", sending = false, listening = true))
    }
}
