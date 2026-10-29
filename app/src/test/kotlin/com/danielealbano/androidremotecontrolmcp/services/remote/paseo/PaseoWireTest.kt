package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import com.danielealbano.androidremotecontrolmcp.services.remote.PermissionReply
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteEvent
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteItem
import com.danielealbano.androidremotecontrolmcp.services.remote.Route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * The frames here were recorded from a running Paseo daemon (0.11.2) by `e2e/paseo/capture.py`,
 * with only names and paths replaced; the cipher vectors were produced by libsodium. Nothing in
 * these samples was written by hand from a reading of the protocol.
 */
@DisplayName("Paseo wire format")
class PaseoWireTest {
    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/paseo/$name")) { "missing $name" }.bufferedReader().readText()

    private fun recorded(
        name: String,
        direction: String,
    ): List<String> =
        resource(name)
            .lines()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { (it["dir"] as JsonPrimitive).content == direction }
            .map { it["frame"].toString() }

    private fun b64(text: String): ByteArray = Base64.getDecoder().decode(text)

    private fun messageOf(frame: String): JsonObject = Json.parseToJsonElement(frame).jsonObject["message"]!!.jsonObject

    private fun live(name: String): List<RemoteEvent> =
        recorded(name, "in").map(PaseoFrames::parse).filterIsInstance<PaseoFrame.Live>().map { it.event }

    private fun JsonObject.text(key: String): String = (this[key] as JsonPrimitive).content

    @Test
    fun `the cipher agrees with libsodium, byte for byte`() {
        val v = Json.parseToJsonElement(resource("nacl-vectors.json")).jsonObject
        assertArrayEquals(b64(v.text("publicA")), NaclBox.publicKey(b64(v.text("secretA"))))
        val shared = NaclBox.sharedKey(b64(v.text("secretA")), b64(v.text("publicB")))
        assertArrayEquals(b64(v.text("shared")), shared)
        assertArrayEquals(shared, NaclBox.sharedKey(b64(v.text("secretB")), b64(v.text("publicA"))))
        val nonce = b64(v.text("nonce"))
        v["boxes"]!!.jsonArray.map { it.jsonObject }.forEach { box ->
            val plain = b64(box.text("plain"))
            assertArrayEquals(b64(box.text("cipher")), NaclBox.seal(shared, nonce, plain))
            assertArrayEquals(plain, NaclBox.open(shared, nonce, b64(box.text("cipher"))))
        }
    }

    @Test
    fun `a box that was altered, or made with another key, does not open`() {
        val mine = NaclBox.keyPair()
        val theirs = NaclBox.keyPair()
        val shared = NaclBox.sharedKey(mine.secret, theirs.public)
        val frame = NaclBox.sealFrame(shared, "收到")
        assertEquals("收到", NaclBox.openFrame(NaclBox.sharedKey(theirs.secret, mine.public), frame))
        val bytes = b64(frame)
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 1).toByte()
        assertNull(NaclBox.openFrame(shared, Base64.getEncoder().encodeToString(bytes)))
        assertNull(NaclBox.openFrame(NaclBox.sharedKey(NaclBox.keyPair().secret, theirs.public), frame))
        assertNull(NaclBox.openFrame(shared, "not base64 !"))
        assertFalse(frame == NaclBox.sealFrame(shared, "收到"))
    }

    @Test
    fun `a pairing link gives the computer's key and relay, and nothing else is accepted as one`() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val relay = """{"endpoint":"relay.paseo.sh:443","useTls":true}"""
        val offer = """{"v":2,"serverId":"srv_TESTSERVER01","daemonPublicKeyB64":"$key","relay":$relay}"""
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(offer.toByteArray())
        val paired = PaseoPairing.read("  https://app.paseo.sh/#offer=$encoded \n", now = 7).getOrThrow()
        assertEquals("srv_TESTSERVER01", paired.peerId)
        assertEquals(key, paired.peerKey)
        assertEquals(listOf(Route(Route.RELAY, "relay.paseo.sh:443", tls = true)), paired.routes)
        assertEquals(PaseoPairing.BACKEND to PaseoPairing.PROTOCOL, paired.backend to paired.protocol)
        assertEquals(7L, paired.addedAt)
        assertEquals(6, paired.fingerprint.length)
        assertTrue(paired.id.isNotEmpty())
        val mine = paired.copy(relayOverride = "relay.example.org:8443")
        assertEquals("relay.example.org:8443", mine.routesToTry().single().endpoint)

        val newer = Base64.getUrlEncoder().encodeToString(offer.replace("\"v\":2", "\"v\":3").toByteArray())
        assertEquals(PaseoPairing.UNSUPPORTED, PaseoPairing.read("#offer=$newer", 0).exceptionOrNull()?.message)
        val shortKey = Base64.getUrlEncoder().encodeToString(offer.replace(key, "AAAA").toByteArray())
        listOf("#offer=$shortKey", "#offer=%%%", "hello", "https://app.paseo.sh/", "host:99999", "").forEach {
            assertEquals(PaseoPairing.NOT_A_LINK, PaseoPairing.read(it, 0).exceptionOrNull()?.message, it)
        }
    }

    @Test
    fun `a direct address is a computer too, with its password kept apart`() {
        val plain = PaseoPairing.read("100.101.102.103:6767", 0).getOrThrow()
        assertEquals(listOf(Route(Route.DIRECT, "100.101.102.103:6767")), plain.routes)
        assertEquals("", plain.peerKey)
        val secured = PaseoPairing.read("tcp://my-pc:6767?ssl=true&password=p%40ss", 0).getOrThrow()
        assertEquals(listOf(Route(Route.DIRECT, "my-pc:6767", tls = true)), secured.routes)
        assertEquals("p@ss", secured.secret)
    }

    @Test
    fun `what this app sends has the shape a real client's frames have`() {
        val sent = recorded("local-claude.jsonl", "out").map { Json.parseToJsonElement(it).jsonObject }
        val byType = sent.associateBy { (it["message"]?.jsonObject ?: it).text("type") }
        val keysOf: (String) -> Set<String> = { frame -> messageOf(frame).keys }
        val recordedKeys: (String) -> Set<String> = { type -> byType.getValue(type)["message"]!!.jsonObject.keys }
        // The recorded client did not subscribe; this app does, or it never hears an agent start or stop.
        assertEquals(recordedKeys("fetch_agents_request") + "subscribe", keysOf(PaseoRequests.listAgents("r")))
        assertEquals(recordedKeys("send_agent_message_request"), keysOf(PaseoRequests.send("r", "a", "hi", "m")))
        val watch = "agent.timeline.set_subscription.request"
        assertEquals(recordedKeys(watch), keysOf(PaseoRequests.watch("r", listOf("a"))))
        assertEquals(recordedKeys("fetch_agent_timeline_request"), keysOf(PaseoRequests.timeline("r", "a")))
        assertEquals(recordedKeys("archive_agent_request"), keysOf(PaseoRequests.archive("r", "a")))
        assertEquals(recordedKeys("create_agent_request"), keysOf(PaseoRequests.createAgent("r", newAgent)))
        val hello = Json.parseToJsonElement(PaseoRequests.hello("c", "1.0")).jsonObject
        assertEquals(byType.getValue("hello").keys, hello.keys)
        assertEquals("mobile", hello.text("clientType"))
    }

    private val newAgent =
        com.danielealbano.androidremotecontrolmcp.services.remote
            .NewAgent("claude", "C:\\work", title = "t")

    @Test
    fun `a recorded exchange reads as who the computer is, its agents, and a streamed answer`() {
        val frames = recorded("local-claude-perm.jsonl", "in").map(PaseoFrames::parse)
        val info = frames.filterIsInstance<PaseoFrame.ServerInfo>().single()
        assertEquals("DESKTOP-TEST", info.hostname)
        assertEquals("srv_TESTSERVER01", info.serverId)
        assertTrue(info.version.isNotEmpty())

        val replies = frames.filterIsInstance<PaseoFrame.Reply>()
        assertTrue(replies.all { it.requestId.isNotEmpty() && it.error.isEmpty() })
        val listed = replies.first { it.agents.isNotEmpty() }.agents.first()
        assertTrue(listed.id.isNotEmpty() && listed.provider.isNotEmpty() && listed.status.isNotEmpty())
        val created = replies.mapNotNull { it.agent }.single()
        assertEquals("brooce-connectivity-test", created.title)
        assertEquals("claude", created.provider)
        assertFalse(created.archived)

        val live = frames.filterIsInstance<PaseoFrame.Live>().map { it.event }
        assertTrue(live.first() is RemoteEvent.TurnStarted)
        val ended = live.last() as RemoteEvent.TurnEnded
        assertEquals("" to false, ended.error to ended.cancelled)
        val items = live.filterIsInstance<RemoteEvent.Item>().map { it.item }
        assertTrue((items.first() as RemoteItem.User).text.contains("echo brooce-permission-test"))
        val tools = items.filterIsInstance<RemoteItem.Tool>()
        assertEquals("Bash", tools.last().name)
        assertEquals("echo brooce-permission-test", tools.last().summary)
        assertEquals("completed", tools.last().status)
        val pieces = items.filterIsInstance<RemoteItem.Assistant>()
        assertTrue(pieces.size >= 2, "the answer arrives in pieces")
        assertEquals(1, pieces.map { it.messageId }.distinct().size)
        val streamed = pieces.joinToString("") { it.text }

        // The history page carries the same answer already put together.
        val history = replies.last { it.items.isNotEmpty() }.items
        assertEquals(streamed, history.filterIsInstance<RemoteItem.Assistant>().single().text)
        assertEquals(1, history.filterIsInstance<RemoteItem.Tool>().size)
        assertTrue(history.first() is RemoteItem.User)
    }

    @Test
    fun `a recorded permission request reads as a card the user can judge, and its answer has the recorded shape`() {
        val asked = live("local-claude-plan.jsonl").filterIsInstance<RemoteEvent.PermissionAsked>()
        val permission = asked.first().permission
        assertTrue(permission.id.startsWith("permission-"))
        assertEquals("ExitPlanMode", permission.name)
        assertTrue(permission.detail.contains("plan-test.txt"), permission.detail)
        assertFalse(permission.detail.startsWith("{"), "the plan itself, not raw JSON")
        val answered = recorded("local-claude-plan.jsonl", "out").map { Json.parseToJsonElement(it).jsonObject }
        val messages = answered.mapNotNull { it["message"]?.jsonObject }
        val recordedAnswer = messages.first { it.text("type") == "agent_permission_response" }
        val mine = messageOf(PaseoRequests.answer("a", "p", PermissionReply(allow = true)))
        assertEquals(recordedAnswer.keys, mine.keys)
        assertEquals(recordedAnswer["response"], mine["response"])
        val denied = messageOf(PaseoRequests.answer("a", "p", PermissionReply(allow = false)))
        assertEquals("deny", denied["response"]!!.jsonObject.text("behavior"))
    }

    @Test
    fun `another computer's Codex agent speaks the same frames`() {
        val frames = recorded("remote-codex.jsonl", "in").map(PaseoFrames::parse)
        val created = frames.filterIsInstance<PaseoFrame.Reply>().mapNotNull { it.agent }.single()
        assertEquals("codex" to "gpt-6.1-sol", created.provider to created.model)
        val pieces = live("remote-codex.jsonl").filterIsInstance<RemoteEvent.Item>().map { it.item }
        assertEquals("收到，测试通过。", pieces.filterIsInstance<RemoteItem.Assistant>().joinToString("") { it.text })
    }

    @Test
    fun `what a computer can start is read from its own answers, models and modes included`() {
        val frames = recorded("remote-catalog.jsonl", "in").map(PaseoFrames::parse)
        val replies = frames.filterIsInstance<PaseoFrame.Reply>()
        val providers = replies.first { it.providers.isNotEmpty() }.providers
        val usable = listOf("claude" to true, "codex" to true, "opencode" to false)
        assertEquals(usable, providers.map { it.id to it.available })
        val codex = replies.first { reply -> reply.models.any { it.id.startsWith("gpt") } }.models
        assertTrue(codex.first().isDefault && codex.first().label.isNotBlank())
        val modes = replies.first { reply -> reply.modes.any { it.id == "full-access" } }.modes
        assertEquals("auto", modes.first().id)
        assertTrue(modes.all { it.label.isNotBlank() })
        // A provider that is switched off answers with why, not with an empty list.
        val local = recorded("local-catalog.jsonl", "in").map(PaseoFrames::parse).filterIsInstance<PaseoFrame.Reply>()
        assertTrue(local.any { it.error == "Provider codex is disabled" && it.models.isEmpty() })
        val withWindow = local.first { it.models.isNotEmpty() }.models.first()
        assertEquals(1_000_000, withWindow.contextWindow)
        // And the questions have the shape a real client sent.
        val asked = recorded("remote-catalog.jsonl", "out").map(::messageOf).associateBy { it.text("type") }
        val mine =
            mapOf(
                "list_available_providers_request" to PaseoCatalogRequests.providers("r"),
                "list_provider_models_request" to PaseoCatalogRequests.models("r", "codex"),
                "list_provider_modes_request" to PaseoCatalogRequests.modes("r", "codex"),
            )
        mine.forEach { (type, frame) -> assertEquals(asked.getValue(type).keys, messageOf(frame).keys) }
    }

    @Test
    fun `a ping is asked the way a real client asked, and the daemon's pong answers it`() {
        val asked = recorded("local-ping.jsonl", "out").map(::messageOf).first()
        val mine = messageOf(PaseoPing.request(asked.text("requestId"), 1L))
        assertEquals(asked.keys, mine.keys)
        assertEquals("ping", mine.text("type"))
        val pong = PaseoFrames.parse(recorded("local-ping.jsonl", "in").first()) as PaseoFrame.Reply
        assertEquals(asked.text("requestId"), pong.requestId)
        assertEquals("", pong.error)
    }

    @Test
    fun `frames from a newer daemon, or no frames at all, are passed over`() {
        val passedOver = listOf("", "{", "[]", """{"type":"session"}""")
        passedOver.forEach { assertEquals(PaseoFrame.Other, PaseoFrames.parse(it), it) }
        // A message type the session does not know is handed on for the feature clients, not dropped.
        val unknown = """{"type":"session","message":{"type":"brand_new","payload":{"x":1}}}"""
        val pushed = PaseoFrames.parse(unknown) as PaseoFrame.Push
        assertEquals("brand_new", pushed.type)
        assertEquals("1", pushed.payload["x"].toString())
        val error = """{"type":"rpc_error","payload":{"requestId":"r1","error":"nope"}}"""
        val failed = PaseoFrames.parse("""{"type":"session","message":$error}""") as PaseoFrame.Reply
        assertEquals("r1", failed.requestId)
        assertEquals("nope", failed.error)
        assertEquals("\"nope\"", failed.raw?.get("error").toString())
    }
}
