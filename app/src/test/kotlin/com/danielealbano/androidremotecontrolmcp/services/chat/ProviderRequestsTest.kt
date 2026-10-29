package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ProviderRequests")
class ProviderRequestsTest {
    private val messages =
        listOf(
            ApiMessage("system", "你是猫"),
            ApiMessage("user", "看这张图", images = listOf(ImageData("image/jpeg", "QUJD"))),
            ApiMessage("assistant", "喵"),
        )

    @Test
    fun `urls are built for both wire formats`() {
        val openAi = ProviderRequests.chatUrl(ProviderKind.OPENAI, "https://x.com/v1/")
        assertEquals("https://x.com/v1/chat/completions", openAi)
        assertEquals("https://x.com/v1/models", ProviderRequests.modelsUrl(ProviderKind.OPENAI, "https://x.com/v1"))
        assertEquals("https://a.com/v1/messages", ProviderRequests.chatUrl(ProviderKind.ANTHROPIC, "https://a.com"))
        assertEquals("https://a.com/v1/messages", ProviderRequests.chatUrl(ProviderKind.ANTHROPIC, "https://a.com/v1"))
    }

    @Test
    fun `the key goes where each api expects it`() {
        assertEquals(mapOf("Authorization" to "Bearer k"), ProviderRequests.headers(ProviderKind.OPENAI, "k"))
        val anthropic = ProviderRequests.headers(ProviderKind.ANTHROPIC, "k")
        assertEquals("k", anthropic["x-api-key"])
        assertTrue(anthropic.containsKey("anthropic-version"))
    }

    @Test
    fun `openai body keeps the system turn and sends images as data urls`() {
        val body = Json.parseToJsonElement(ProviderBodies.openAi("gpt", messages)).jsonObject
        assertEquals("gpt", body["model"]!!.jsonPrimitive.content)
        val turns = body["messages"]!!.jsonArray
        assertEquals("system", turns[0].jsonObject["role"]!!.jsonPrimitive.content)
        val parts = turns[1].jsonObject["content"]!!.jsonArray
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        val url =
            parts[1]
                .jsonObject["image_url"]!!
                .jsonObject["url"]!!
                .jsonPrimitive.content
        assertEquals("data:image/jpeg;base64,QUJD", url)
        assertEquals("喵", turns[2].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic body lifts the system turn out and uses base64 image blocks`() {
        val body = Json.parseToJsonElement(ProviderBodies.anthropic("claude", messages)).jsonObject
        assertEquals("你是猫", body["system"]!!.jsonPrimitive.content)
        val turns = body["messages"]!!.jsonArray
        assertEquals(2, turns.size)
        val parts = turns[0].jsonObject["content"]!!.jsonArray
        assertEquals("image", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "QUJD",
            parts[0]
                .jsonObject["source"]!!
                .jsonObject["data"]!!
                .jsonPrimitive.content,
        )
        assertEquals("看这张图", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `replies are parsed and errors surfaced`() {
        val ok = ProviderReplies.openAi("""{"choices":[{"message":{"role":"assistant","content":"你好"}}]}""")
        assertEquals(ChatApiResult(ok = true, content = "你好"), ok)
        val parts = """[{"type":"text","text":"分"},{"type":"text","text":"段"}]"""
        val partsBody = """{"choices":[{"message":{"content":$parts}}]}"""
        assertEquals("分段", ProviderReplies.openAi(partsBody).content)
        val error = ProviderReplies.openAi("""{"error":{"message":"bad key"}}""")
        assertFalse(error.ok)
        assertEquals("bad key", error.error)
        val anthropicBody = """{"content":[{"type":"text","text":"嗨"},{"type":"tool_use"}]}"""
        assertEquals("嗨", ProviderReplies.anthropic(anthropicBody).content)
        assertFalse(ProviderReplies.anthropic("not json").ok)
        assertEquals("嗨", ProviderRequests.parseReply(ProviderKind.ANTHROPIC, anthropicBody).content)
    }
}
