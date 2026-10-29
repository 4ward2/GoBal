package com.danielealbano.androidremotecontrolmcp.services.provider

import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("ProviderIntentParser")
class ProviderIntentParserTest {
    private val genericKey = "sk-" + "0123456789abcdef".repeat(2)

    @Nested
    @DisplayName("keys")
    inner class Keys {
        @Test
        fun `a pasted key alone opens the card with the key and no guessed service`() {
            val draft = ProviderIntentParser.parse(genericKey)!!
            assertEquals(genericKey, draft.apiKey)
            assertNull(draft.presetId)
            assertEquals("", draft.baseUrl)
        }

        @Test
        fun `a key whose shape is unique picks its service`() {
            val key = "sk-ant-api03-" + "Ab1".repeat(10)
            val draft = ProviderIntentParser.parse("帮我用这个 $key")!!
            assertEquals("anthropic", draft.presetId)
            assertEquals(ProviderKind.ANTHROPIC, draft.kind)
            assertEquals(key, draft.apiKey)
        }

        @Test
        fun `a key inside ordinary chat is still caught so it never reaches a model`() {
            val draft = ProviderIntentParser.parse("你看看这个格式对不对 $genericKey 谢谢")
            assertEquals(genericKey, draft?.apiKey)
        }

        @Test
        fun `a named service wins over the shape of the key`() {
            val draft = ProviderIntentParser.parse("把 DeepSeek 的密钥换成 $genericKey")!!
            assertEquals("deepseek", draft.presetId)
            assertEquals("https://api.deepseek.com/v1", draft.baseUrl)
            assertEquals(genericKey, draft.apiKey)
        }

        @Test
        fun `a UUID-style key counts when the sentence is about keys`() {
            val uuid = "3f2a9c1e-7b4d-4e8a-9c2b-1d5e6f7a8b9c"
            assertEquals(uuid, ProviderIntentParser.parse("豆包的 API key 是 $uuid")?.apiKey)
            assertNull(ProviderIntentParser.parse("订单号 $uuid 到哪了"))
        }
    }

    @Nested
    @DisplayName("naming a service")
    inner class Naming {
        @Test
        fun `add a service by name without a key`() {
            val draft = ProviderIntentParser.parse("添加服务商 DeepSeek")!!
            assertEquals("deepseek", draft.presetId)
            assertEquals("DeepSeek", draft.name)
            assertEquals("", draft.apiKey)
        }

        @Test
        fun `a recogniser's spaced-out name still matches`() {
            assertEquals("deepseek", ProviderIntentParser.parse("添加 deep seek 服务 商")?.presetId)
            assertEquals("qwen", ProviderIntentParser.parse("接入通义千问")?.presetId)
        }

        @Test
        fun `command code is found by name or by its key`() {
            assertEquals("commandcode", ProviderIntentParser.parse("添加 command code 服务商")?.presetId)
            val draft = ProviderIntentParser.parse("user_" + "Zz9".repeat(20))!!
            assertEquals("commandcode", draft.presetId)
            assertEquals("https://api.commandcode.ai/provider/v1", draft.baseUrl)
        }

        @Test
        fun `the longest alias wins`() {
            assertEquals("stepfun", ProviderIntentParser.parse("添加阶跃星辰")?.presetId)
        }

        @Test
        fun `a loose alias needs the topic`() {
            assertNull(ProviderIntentParser.parse("添加小米的联系人"))
            assertEquals("mimo", ProviderIntentParser.parse("添加小米的服务商")?.presetId)
        }

        @Test
        fun `a custom address with a key makes a custom service named after its host`() {
            val draft = ProviderIntentParser.parse("添加服务商 https://api.example.com/v1/ $genericKey")!!
            assertNull(draft.presetId)
            assertEquals("https://api.example.com/v1", draft.baseUrl)
            assertEquals("example", draft.name)
            assertEquals(ProviderKind.OPENAI, draft.kind)
        }

        @Test
        fun `a documentation link is kept to be read, not taken as the API address`() {
            val draft = ProviderIntentParser.parse("添加服务商 https://docs.acme.ai/quickstart $genericKey")!!
            assertEquals("https://docs.acme.ai/quickstart", draft.sourceUrl)
            assertEquals("", draft.baseUrl)
            assertNull(draft.presetId)
        }

        @Test
        fun `a pasted curl example gives the base address, the key and the model`() {
            val curl =
                "curl https://api.acme.ai/v1/chat/completions -H \"Authorization: Bearer $genericKey\" " +
                    "-d '{\"model\": \"acme-large\", \"messages\": []}'"
            val draft = ProviderIntentParser.parse(curl)!!
            assertEquals("https://api.acme.ai/v1", draft.baseUrl)
            assertEquals(genericKey, draft.apiKey)
            assertEquals("acme-large", draft.model)
            assertEquals("", draft.sourceUrl)
        }

        @Test
        fun `an example against messages is taken as Anthropic`() {
            val draft = ProviderIntentParser.parse("key $genericKey https://api.acme.ai/v1/messages")!!
            assertEquals(ProviderKind.ANTHROPIC, draft.kind)
            assertEquals("https://api.acme.ai/v1", draft.baseUrl)
        }

        @Test
        fun `a preset's own address is recognised`() {
            val draft = ProviderIntentParser.parse("API 地址 https://api.moonshot.cn/v1")!!
            assertEquals("kimi", draft.presetId)
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "DeepSeek 好用吗",
            "打开 kimi",
            "帮我下载豆包",
            "我的手机号是 13812345678",
            "看看这个 https://example.com/page",
            "that was rapid",
            "commit 3f2a9c1e7b4d4e8a9c2b1d5e6f7a8b9c0d1e2f3a",
            "今天天气怎么样",
            "添加一个闹钟",
        ],
    )
    fun `ordinary sentences are left alone`(text: String) {
        assertNull(ProviderIntentParser.parse(text))
    }
}
