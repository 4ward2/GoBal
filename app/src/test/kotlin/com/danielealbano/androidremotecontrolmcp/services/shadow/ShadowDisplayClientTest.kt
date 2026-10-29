package com.danielealbano.androidremotecontrolmcp.services.shadow

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

@DisplayName("ShadowDisplayClient")
class ShadowDisplayClientTest {
    private fun parse(line: String) = Json.parseToJsonElement(line).jsonObject

    @Test
    fun `encode puts cmd first and ignores a cmd param`() {
        val params = mapOf("cmd" to JsonPrimitive("destroy"), "package" to JsonPrimitive("a.b"))
        val obj = parse(ShadowProtocol.encode("open", params))
        assertEquals(JsonPrimitive("open"), obj["cmd"])
        assertEquals(JsonPrimitive("a.b"), obj["package"])
    }

    @Test
    fun `encode rejects blank cmd`() {
        assertThrows<IllegalArgumentException> { ShadowProtocol.encode(" ", emptyMap()) }
    }

    @Test
    fun `decode ok reply`() {
        val r = ShadowProtocol.decode("""{"ok":true,"display_id":7}""")
        val reply = r.getOrThrow()
        assertEquals(JsonPrimitive(7), reply["display_id"])
    }

    @Test
    fun `decode error reply carries daemon message`() {
        val r = ShadowProtocol.decode("""{"ok":false,"error":"no such shadow display"}""")
        assertEquals("no such shadow display", r.exceptionOrNull()?.message)
    }

    @Test
    fun `decode null or malformed reply fails`() {
        assertTrue(ShadowProtocol.decode(null).isFailure)
        assertTrue(ShadowProtocol.decode("not json").exceptionOrNull() is ShadowDisplayException)
        assertTrue(ShadowProtocol.decode("""{"display_id":1}""").isFailure)
    }

    @Test
    fun `client sends one line and returns the reply`() =
        runTest {
            var sent: String? = null
            val client =
                TransportShadowDisplayClient { line ->
                    sent = line
                    """{"ok":true,"displays":[]}"""
                }
            val reply = client.request("status").getOrThrow()
            assertTrue(reply.containsKey("displays"))
            assertEquals(JsonPrimitive("status"), parse(sent.orEmpty())["cmd"])
        }

    @Test
    fun `unreachable daemon becomes a readable failure`() =
        runTest {
            val client = TransportShadowDisplayClient { throw IOException("Connection refused") }
            val e = client.request("status").exceptionOrNull()
            assertTrue(e is ShadowDisplayException)
            assertTrue(e?.message.orEmpty().contains("not running"))
        }
}
