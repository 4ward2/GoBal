package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModelCatalog
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModelGate
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModels
import com.danielealbano.androidremotecontrolmcp.services.localmodel.ServerLaunch
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteChats
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ModelGateway")
class ModelGatewayTest {
    private val provider = ModelProvider("p1", "测试", ProviderKind.OPENAI, "https://x.com/v1", "key", listOf("m1", "m2"))
    private val onPhone =
        ModelProvider(
            id = LocalModels.PROVIDER_ID,
            name = "本机模型",
            baseUrl = ServerLaunch.BASE_URL,
            models = listOf("glm-edge-4b-chat"),
        )
    private val loopback = mockk<PhoneMcpLoopbackClient>()
    private val settings =
        mockk<AssistSettingsRepository> { every { providers } returns flowOf(listOf(provider, onPhone)) }
    private val http = FakeHttp()
    private val remote = mockk<RemoteChats> { every { handles(any()) } returns false }
    private val local = mockk<LocalModelGate>()
    private val gateway = ModelGateway(loopback, settings, http, local, remote)

    @Test
    fun `the on-device model is woken before the request goes to it`() =
        runTest {
            coEvery { local.wake("glm-edge-4b-chat") } returns null
            every { local.spec(any()) } returns null
            http.reply = HttpReply(200, """{"choices":[{"message":{"content":"本机"}}]}""")
            val result = gateway.chat(turns, ModelChoice(LocalModels.PROVIDER_ID, ""))
            assertEquals("本机", result.content)
            assertEquals("${ServerLaunch.BASE_URL}/chat/completions", http.calls.single().url)
            assertEquals(300_000, http.calls.single().timeoutMs)
            coVerify { local.wake("glm-edge-4b-chat") }
        }

    @Test
    fun `a text-only on-device model gets no pictures and a conversation cut to its window`() =
        runTest {
            coEvery { local.wake(any()) } returns null
            every { local.spec("glm-edge-4b-chat") } returns LocalModelCatalog.GLM_EDGE_4B
            http.reply = HttpReply(200, """{"choices":[{"message":{"content":"好"}}]}""")
            val picture =
                java.io.File
                    .createTempFile("pic", ".jpg")
                    .apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val history = (1..400).map { ChatMessage("user", "第${it}句话，说点什么吧") }
            val turns = history + ChatMessage("user", "看图", images = listOf(picture))
            gateway.chat(turns, ModelChoice(LocalModels.PROVIDER_ID, "glm-edge-4b-chat"))
            val body = http.calls.single().body
            assertFalse(body.contains("image_url"))
            assertFalse(body.contains("第1句话"))
            assertTrue(body.contains("看图"))
        }

    @Test
    fun `when the on-device model cannot wake the reason is returned and nothing is sent`() =
        runTest {
            coEvery { local.wake(any()) } returns "内存不够"
            val result = gateway.chatStream(turns, ModelChoice(LocalModels.PROVIDER_ID, "glm-edge-4b-chat")) {}
            assertFalse(result.ok)
            assertEquals("内存不够", result.error)
            assertTrue(http.calls.isEmpty())
        }

    @Test
    fun `probing the on-device model only looks at its files`() =
        runTest {
            every { local.installed("glm-edge-4b-chat") } returns true
            assertTrue(gateway.probe(onPhone))
            assertTrue(http.calls.isEmpty())
            coVerify(exactly = 0) { local.wake(any()) }
        }

    private val turns = listOf(ChatMessage("system", "s"), ChatMessage("user", "hi"))

    @Test
    fun `no choice means the phone's own provider through the loopback server`() =
        runTest {
            every { loopback.chat(any(), any()) } returns ChatApiResult(ok = true, content = "机上")
            val result = gateway.chat(turns, ModelChoice.ON_DEVICE)
            assertEquals("机上", result.content)
            verify { loopback.chat(listOf("system" to "s", "user" to "hi"), any()) }
            assertTrue(http.calls.isEmpty())
        }

    @Test
    fun `a chosen provider is called with its key and model`() =
        runTest {
            http.reply = HttpReply(200, """{"choices":[{"message":{"content":"云端"}}]}""")
            val result = gateway.chat(turns, ModelChoice("p1", "m2"))
            assertEquals("云端", result.content)
            val call = http.calls.single()
            assertEquals("https://x.com/v1/chat/completions", call.url)
            assertEquals("Bearer key", call.headers["Authorization"])
            assertTrue(call.body.contains("\"model\":\"m2\""))
        }

    @Test
    fun `a blank model falls back to the provider's first model`() =
        runTest {
            http.reply = HttpReply(200, """{"choices":[{"message":{"content":"ok"}}]}""")
            gateway.chat(turns, ModelChoice("p1", ""))
            assertTrue(
                http.calls
                    .single()
                    .body
                    .contains("\"model\":\"m1\""),
            )
        }

    @Test
    fun `http errors become readable failures`() =
        runTest {
            http.reply = HttpReply(401, """{"error":{"message":"invalid api key"}}""")
            val result = gateway.chat(turns, ModelChoice("p1", "m1"))
            assertFalse(result.ok)
            assertEquals("HTTP 401: invalid api key", result.error)
        }

    @Test
    fun `an unknown provider id falls back to the loopback path`() =
        runTest {
            every { loopback.chat(any(), any()) } returns ChatApiResult(ok = true, content = "机上")
            assertEquals("机上", gateway.chat(turns, ModelChoice("gone", "m")).content)
        }

    @Test
    fun `model listing goes through GET models and keeps only chat models`() =
        runTest {
            http.reply = HttpReply(200, """{"data":[{"id":"z","context_length":8192},{"id":"a"},{"id":"a-tts"}]}""")
            assertEquals(listOf(ModelInfo("a"), ModelInfo("z", 8192)), gateway.listModels(provider).getOrThrow())
            assertEquals("https://x.com/v1/models", http.calls.single().url)
        }

    @Test
    fun `a public model list is only read after the key-only endpoint accepts the key`() =
        runTest {
            val openRouter = provider.copy(baseUrl = "https://openrouter.ai/api/v1")
            http.reply = HttpReply(401, "bad key")
            assertTrue(gateway.listModels(openRouter).isFailure)
            assertEquals(listOf("https://openrouter.ai/api/v1/key"), http.calls.map { it.url })
        }

    @Test
    fun `a key-only endpoint that no longer exists does not block the listing`() =
        runTest {
            val commandCode = provider.copy(baseUrl = "https://api.commandcode.ai/provider/v1")
            http.reply = HttpReply(404, "gone")
            assertTrue(gateway.listModels(commandCode).isFailure)
            assertEquals(
                listOf("https://api.commandcode.ai/alpha/whoami", "https://api.commandcode.ai/provider/v1/models"),
                http.calls.map { it.url },
            )
        }

    @Test
    fun `a service with a public model list is probed on its key-only endpoint`() =
        runTest {
            val openRouter = provider.copy(baseUrl = "https://openrouter.ai/api/v1")
            http.reply = HttpReply(401, "bad key")
            assertFalse(gateway.probe(openRouter))
            assertEquals("https://openrouter.ai/api/v1/key", http.calls.last().url)
        }

    @Test
    fun `chatStream hands deltas out as they arrive and returns the whole reply`() =
        runTest {
            http.lines =
                listOf(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}",
                    "",
                    "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}",
                    "data: [DONE]",
                )
            val deltas = mutableListOf<String>()
            val result = gateway.chatStream(turns, ModelChoice("p1", "m1")) { deltas += it }
            assertEquals(listOf("你", "好"), deltas)
            assertEquals(ChatApiResult(ok = true, content = "你好"), result)
            assertTrue(
                http.calls
                    .single()
                    .body
                    .startsWith("{\"stream\":true,"),
            )
        }

    @Test
    fun `chatStream keeps a reasoning model's thinking out of the reply and returns it separately`() =
        runTest {
            http.lines =
                listOf(
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"用户在打招呼\"}}]}",
                    "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}",
                    "data: [DONE]",
                )
            val deltas = mutableListOf<String>()
            val result = gateway.chatStream(turns, ModelChoice("p1", "m1")) { deltas += it }
            assertEquals(listOf("你好"), deltas)
            assertEquals(ChatApiResult(ok = true, content = "你好", reasoning = "用户在打招呼"), result)
        }

    @Test
    fun `chatStream on the device path emits the whole reply once`() =
        runTest {
            every { loopback.chat(any(), any()) } returns ChatApiResult(ok = true, content = "机上")
            val deltas = mutableListOf<String>()
            gateway.chatStream(turns, ModelChoice.ON_DEVICE) { deltas += it }
            assertEquals(listOf("机上"), deltas)
        }

    @Test
    fun `probe is happy with a model listing, falls back to a chat when the endpoint is missing`() =
        runTest {
            http.reply = HttpReply(200, """{"data":[]}""")
            assertTrue(gateway.probe(provider))
            http.reply = HttpReply(401, """{"error":{"message":"bad key"}}""")
            assertFalse(gateway.probe(provider))
            val chat = HttpReply(200, """{"choices":[{"message":{"content":"hi"}}]}""")
            http.replies = ArrayDeque(listOf(HttpReply(404, "no"), chat))
            assertTrue(gateway.probe(provider))
            assertEquals("https://x.com/v1/chat/completions", http.calls.last().url)
        }

    private class FakeHttp : ProviderHttp {
        data class Call(
            val url: String,
            val headers: Map<String, String>,
            val body: String,
            val timeoutMs: Int = 0,
        )

        var reply = HttpReply(200, "{}")
        var replies = ArrayDeque<HttpReply>()
        var lines: List<String> = emptyList()
        val calls = mutableListOf<Call>()

        override fun stream(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Int,
            onLine: (String) -> Unit,
        ): HttpReply {
            calls += Call(url, headers, body, timeoutMs)
            lines.forEach(onLine)
            return HttpReply(200, "")
        }

        private fun next(): HttpReply = replies.removeFirstOrNull() ?: reply

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Int,
        ): HttpReply {
            calls += Call(url, headers, body, timeoutMs)
            return next()
        }

        override fun get(
            url: String,
            headers: Map<String, String>,
            timeoutMs: Int,
        ): HttpReply {
            calls += Call(url, headers, "")
            return next()
        }
    }
}
