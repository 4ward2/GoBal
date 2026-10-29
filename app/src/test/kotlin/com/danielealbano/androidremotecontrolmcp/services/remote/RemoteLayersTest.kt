package com.danielealbano.androidremotecontrolmcp.services.remote

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.ClientIdentity
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.PaseoPairing
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.PaseoSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** A channel the test drives by hand: what the session sends is kept, what the computer says is fed in. */
private class FakeChannel : SecureChannel {
    override val status = MutableStateFlow(LinkStatus(LinkState.CONNECTING))
    override val incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val sent = mutableListOf<String>()
    var closed = false

    override fun send(message: String): Boolean {
        sent += message
        return true
    }

    override fun close() {
        closed = true
    }

    fun typesSent(): List<String> =
        sent.map { frame ->
            val root = Json.parseToJsonElement(frame).jsonObject
            ((root["message"]?.jsonObject ?: root)["type"] as JsonPrimitive).content
        }

    fun lastRequestId(): String {
        val message = Json.parseToJsonElement(sent.last()).jsonObject["message"]!!.jsonObject
        return (message["requestId"] as JsonPrimitive).content
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Remote layers")
class RemoteLayersTest {
    @TempDir
    lateinit var dir: File

    private fun route(
        endpoint: String,
        tls: Boolean = false,
        kind: String = Route.DIRECT,
    ) = Route(kind, endpoint, tls)

    @Test
    fun `an unencrypted direct route is only for addresses inside a private network`() {
        val inside =
            listOf("192.168.1.8", "10.0.0.2", "172.20.1.1", "100.101.102.103", "127.0.0.1") +
                listOf("[fd7a:115c::1]", "localhost")
        inside.forEach { assertNull(RoutePolicy.refusal(route("$it:6767")), it) }
        val outside =
            listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "my-pc.example.com", "frp.example.org") +
                listOf("my-pc", "[2001:db8::1]")
        outside.forEach { assertEquals(RoutePolicy.NEEDS_TLS, RoutePolicy.refusal(route("$it:6767")), it) }
        assertNull(RoutePolicy.refusal(route("my-pc.example.com:443", tls = true)))
        assertNull(RoutePolicy.refusal(route("relay.paseo.sh:80", kind = Route.RELAY)))
    }

    @Test
    fun `a failed connection is explained by what actually went wrong`() {
        val lan = route("192.168.1.8:6767")
        val domain = route("pc.example.com:443", tls = true)
        val relay = route("relay.paseo.sh:443", tls = true, kind = Route.RELAY)
        val refused = ConnectException()
        assertEquals(LinkDiagnosis.VPN_CONFLICT, LinkDiagnosis.explain(LinkFailure(lan, refused, vpnActive = true)))
        assertEquals(LinkDiagnosis.CLOSED_PORT, LinkDiagnosis.explain(LinkFailure(lan, refused)))
        assertEquals(LinkDiagnosis.CLOSED_PORT, LinkDiagnosis.explain(LinkFailure(domain, refused, vpnActive = true)))
        assertEquals(LinkDiagnosis.NO_SUCH_NAME, LinkDiagnosis.explain(LinkFailure(domain, UnknownHostException())))
        assertEquals(LinkDiagnosis.CERTIFICATE, LinkDiagnosis.explain(LinkFailure(domain, SSLHandshakeException("x"))))
        assertEquals(LinkDiagnosis.WRONG_PASSWORD, LinkDiagnosis.explain(LinkFailure(lan, httpCode = 401)))
        assertEquals(LinkDiagnosis.NOT_PAIRED, LinkDiagnosis.explain(LinkFailure(relay, httpCode = 403)))
        assertEquals(LinkDiagnosis.NO_ANSWER, LinkDiagnosis.explain(LinkFailure(relay)))
        assertTrue(LinkDiagnosis.isRefusal(LinkFailure(lan, httpCode = 401)))
        assertFalse(LinkDiagnosis.isRefusal(LinkFailure(lan, ConnectException())))
    }

    @Test
    fun `an answer is one message, its pieces joined and each tool call shown once`() {
        val answer = AnswerText()
        val running = RemoteItem.Tool("c1", "Bash", "", "running")
        val named = running.copy(summary = "echo hi")
        assertEquals("", answer.add(running))
        assertEquals("> Bash：echo hi", answer.add(named))
        assertEquals("", answer.add(named.copy(status = "completed")))
        assertEquals("\n\n命令已执行，", answer.add(RemoteItem.Assistant("命令已执行，", "m1")))
        assertEquals("输出 hi。", answer.add(RemoteItem.Assistant("输出 hi。", "m1")))
        assertEquals("\n\n再说一句。", answer.add(RemoteItem.Assistant("再说一句。", "m2")))
        assertEquals("> Bash：echo hi\n\n命令已执行，输出 hi。\n\n再说一句。", answer.text())
    }

    @Test
    fun `a conversation read back from the computer becomes the same messages a live one leaves`() {
        val items =
            listOf(
                RemoteItem.User("跑一下", "u1"),
                RemoteItem.Reasoning("先看看"),
                RemoteItem.Tool("c1", "Bash", "echo hi", "completed"),
                RemoteItem.Assistant("输出 hi。", "m1"),
                RemoteItem.User("谢谢", "u2"),
                RemoteItem.Assistant("不客气", "m2"),
            )
        val bubbles = RemoteText.bubbles(items)
        assertEquals(listOf("user", "assistant", "user", "assistant"), bubbles.map { it.role })
        assertEquals("> Bash：echo hi\n\n输出 hi。", bubbles[1].content)
        assertEquals("先看看", bubbles[1].reasoning)
        assertEquals("ru-u1", bubbles[0].id)
        assertEquals(bubbles.map { it.id }.distinct().size, bubbles.size)
        assertTrue(RemoteText.bubbles(emptyList()).isEmpty())
    }

    @Test
    fun `a contact that is an agent carries which computer and which agent, and nothing else is one`() {
        val contact = RemoteContact("pc-1", "agent-9")
        assertEquals(contact, RemoteContact.of(contact.choice()))
        assertNull(RemoteContact.of(ModelChoice("provider-1", "gpt")))
        assertNull(RemoteContact.of(ModelChoice()))
        assertNull(RemoteContact.of(ModelChoice("remote:pc-1", "")))
    }

    @Test
    fun `paired computers are stored sealed, and a file that cannot be opened reads as nothing paired`() {
        val sealer =
            object : Sealer {
                override fun seal(plain: ByteArray): ByteArray = ByteArray(plain.size) { flip(plain[it]) }

                private fun flip(byte: Byte): Byte = (byte.toInt() xor 0x5a).toByte()

                override fun open(sealed: ByteArray): ByteArray = seal(sealed)
            }
        val file = File(dir, "remote/computers.bin")
        val store = ComputersStore(file, sealer)
        assertEquals(ComputersFile(), store.load())
        val read = PaseoPairing.read("tcp://10.0.0.2:6767?password=s3cret", now = 5).getOrThrow()
        val computer = read.copy(name = "书房电脑")
        store.write(ComputersFile(clientId = "c1", computers = listOf(computer)))
        val onDisk = file.readText()
        assertFalse(onDisk.contains("s3cret") || onDisk.contains("10.0.0.2") || onDisk.contains("书房"))
        val back = store.load()
        assertEquals(listOf(computer), back.computers)
        val stored = back.computers.single()
        assertEquals(PaseoPairing.BACKEND to PaseoPairing.PROTOCOL, stored.backend to stored.protocol)
        assertEquals(ComputersFile.FORMAT, back.format)
        file.writeText("garbage")
        assertEquals(ComputersFile(), store.load())
    }

    private fun recorded(direction: String): List<String> =
        checkNotNull(javaClass.getResourceAsStream("/paseo/local-claude-perm.jsonl"))
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { (it["dir"] as JsonPrimitive).content == direction }
            .map { it["frame"].toString() }

    @Test
    fun `the session greets, learns who the computer is, and only then is online`() =
        runTest(UnconfinedTestDispatcher()) {
            val channel = FakeChannel()
            val session = PaseoSession(channel, ClientIdentity("c1", "1.0"), "srv_TESTSERVER01", backgroundScope)
            assertEquals(LinkState.CONNECTING, session.status.value.state)
            assertTrue(session.refresh().isFailure, "nothing is asked before the computer has answered")
            channel.status.value = LinkStatus(LinkState.ONLINE)
            assertEquals(listOf("hello"), channel.typesSent())
            assertEquals(LinkState.CONNECTING, session.status.value.state)
            val frames = recorded("in")
            channel.incoming.emit(frames[0])
            assertEquals(LinkState.ONLINE, session.status.value.state)
            assertEquals("DESKTOP-TEST", session.computerName.value)
            assertEquals(listOf("hello", "fetch_agents_request"), channel.typesSent())

            // Live frames reach whoever listens, as the app's own events, and an answer comes back to its asker.
            val heard = mutableListOf<RemoteEvent>()
            backgroundScope.launch { session.events.collect { heard += it } }
            frames.drop(1).forEach { channel.incoming.emit(it) }
            assertTrue(heard.first() is RemoteEvent.TurnStarted)
            assertTrue(heard.last() is RemoteEvent.TurnEnded)
            var sent: Result<Unit>? = null
            backgroundScope.launch { sent = session.send("a1", "hi") }
            val id = channel.lastRequestId()
            val payload = """{"requestId":"$id","accepted":false,"error":"busy"}"""
            val refusal = """{"type":"send_agent_message_response","payload":$payload}"""
            channel.incoming.emit("""{"type":"session","message":$refusal}""")
            assertEquals("busy", sent?.exceptionOrNull()?.message)

            // The link going down fails what is waiting and says why.
            var listed: Result<List<RemoteAgent>>? = null
            backgroundScope.launch { listed = session.refresh() }
            channel.status.value = LinkStatus(LinkState.OFFLINE, LinkDiagnosis.CLOSED_PORT)
            assertEquals(LinkDiagnosis.CLOSED_PORT, listed?.exceptionOrNull()?.message)
            assertEquals(LinkState.OFFLINE, session.status.value.state)
            assertFalse(session.answer("a1", "p1", PermissionReply(allow = true)), "no answer is sent into a dead link")
        }

    @Test
    fun `a different computer answering at the same address is refused`() =
        runTest(UnconfinedTestDispatcher()) {
            val channel = FakeChannel()
            val session = PaseoSession(channel, ClientIdentity("c1", "1.0"), "srv_SOMEONE_ELSE", backgroundScope)
            channel.status.value = LinkStatus(LinkState.ONLINE)
            channel.incoming.emit(recorded("in")[0])
            assertEquals(LinkStatus(LinkState.REJECTED, PaseoSession.ANOTHER_COMPUTER), session.status.value)
            assertTrue(channel.closed)
        }
}
