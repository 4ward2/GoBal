package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModelGate
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteChats
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@DisplayName("ModelGateway: what is sent is cut to the window, and a reply can be stopped")
class ModelGatewayBudgetTest {
    private val small =
        ModelProvider("p1", "测试", ProviderKind.OPENAI, "https://x.com/v1", "key", listOf("m1"), contextWindow = 400)
    private val settings = mockk<AssistSettingsRepository> { every { providers } returns flowOf(listOf(small)) }
    private val loopback = mockk<PhoneMcpLoopbackClient>()
    private val remote = mockk<RemoteChats> { every { handles(any()) } returns false }
    private val local = mockk<LocalModelGate>()

    /** Records the last request; a streamed request blocks until aborted when [hang] is set. */
    private class FakeHttp(
        val hang: Boolean = false,
    ) : ProviderHttp {
        var body = ""
        val opened = CountDownLatch(1)
        val aborted = CountDownLatch(1)

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Int,
        ): HttpReply {
            this.body = body
            return HttpReply(200, """{"choices":[{"message":{"content":"好"}}]}""")
        }

        override fun get(
            url: String,
            headers: Map<String, String>,
            timeoutMs: Int,
        ): HttpReply = HttpReply(200, "{}")

        override fun stream(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Int,
            onLine: (String) -> Unit,
        ): HttpReply {
            this.body = body
            onLine("data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}")
            return HttpReply(200, "")
        }

        override fun streamAbortable(
            request: StreamRequest,
            onOpen: (abort: () -> Unit) -> Unit,
            onLine: (String) -> Unit,
        ): HttpReply {
            if (!hang) return stream(request.url, request.headers, request.body, request.timeoutMs, onLine)
            onOpen { aborted.countDown() }
            opened.countDown()
            // A silent server: only closing the connection ends the read.
            check(aborted.await(WAIT_S, TimeUnit.SECONDS)) { "never aborted" }
            throw IOException("Socket closed")
        }
    }

    private val long =
        (1..200).flatMap {
            listOf(ChatMessage("user", "第${it}个问题，说点什么吧"), ChatMessage("assistant", "第${it}个回答"))
        }
    private val turns = listOf(ChatMessage("system", "系统")) + long + ChatMessage("user", "最后一问")

    @Test
    fun `a conversation longer than a cloud model's window loses its oldest turns, not its newest`() =
        runTest {
            val http = FakeHttp()
            val gateway = ModelGateway(loopback, settings, http, local, remote)
            assertTrue(gateway.chat(turns, ModelChoice("p1", "m1")).ok)
            assertFalse(http.body.contains("第1个问题"))
            assertTrue(http.body.contains("最后一问"))
            assertTrue(http.body.contains("系统"))
        }

    @Test
    fun `the streamed request is cut the same way`() =
        runTest {
            val http = FakeHttp()
            val gateway = ModelGateway(loopback, settings, http, local, remote)
            assertTrue(gateway.chatStream(turns, ModelChoice("p1", "m1")) {}.ok)
            assertFalse(http.body.contains("第1个问题"))
            assertTrue(http.body.contains("最后一问"))
        }

    @Test
    fun `a short conversation is sent whole`() =
        runTest {
            val http = FakeHttp()
            val gateway = ModelGateway(loopback, settings, http, local, remote)
            val short = listOf(ChatMessage("system", "系统"), ChatMessage("assistant", "喵"), ChatMessage("user", "在吗"))
            gateway.chat(short, ModelChoice("p1", "m1"))
            assertTrue(http.body.contains("喵"))
            assertTrue(http.body.contains("在吗"))
        }

    @Test
    fun `turns kept after a cut start at a user turn`() {
        val fitted = ContextBudget.fit(turns, window = 400)
        assertEquals("system", fitted.first().role)
        assertEquals("user", fitted[1].role)
        assertEquals("最后一问", fitted.last().text)
    }

    @Test
    fun `cancelling while the model is silent closes the connection`() {
        val http = FakeHttp(hang = true)
        val gateway = ModelGateway(loopback, settings, http, local, remote)
        runBlocking {
            val question = listOf(ChatMessage("user", "在吗"))
            val job = launch(Dispatchers.IO) { gateway.chatStream(question, ModelChoice("p1", "m1")) {} }
            assertTrue(http.opened.await(WAIT_S, TimeUnit.SECONDS))
            job.cancelAndJoin()
        }
        assertEquals(0, http.aborted.count)
    }

    private companion object {
        const val WAIT_S = 10L
    }
}
