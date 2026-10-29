package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaseoDaemonAdminTest {
    private val mockRpc = mockk<PaseoRpc>()

    @Test
    fun `config handles missing relay gracefully`() =
        runBlocking {
            val payload =
                buildJsonObject {
                    put("listen", "127.0.0.1:8080")
                    put("version", "0.11.0")
                }

            coEvery { mockRpc.call("get_daemon_config_request") } returns Result.success(payload)

            val admin = PaseoDaemonAdmin(mockRpc)
            val result = admin.config()

            assertTrue(result.isSuccess)
            assertEquals(false, result.getOrNull()?.relayEnabled)
        }

    @Test
    fun `diagnose returns provider health`() =
        runBlocking {
            val payload =
                buildJsonObject {
                    put("provider", "claude")
                    put("diagnostic", "")
                }

            coEvery { mockRpc.call("provider_diagnostic_request", any()) } returns Result.success(payload)

            val admin = PaseoDaemonAdmin(mockRpc)
            val result = admin.diagnose("claude")

            assertTrue(result.isSuccess)
            assertEquals("claude", result.getOrNull()?.provider)
            assertEquals(true, result.getOrNull()?.ok)
            assertEquals("OK", result.getOrNull()?.message)
        }

    @Test
    fun `diagnose reports provider error`() =
        runBlocking {
            val payload =
                buildJsonObject {
                    put("provider", "codex")
                    put("diagnostic", "Connection refused: 127.0.0.1:5001")
                }

            coEvery { mockRpc.call("provider_diagnostic_request", any()) } returns Result.success(payload)

            val admin = PaseoDaemonAdmin(mockRpc)
            val result = admin.diagnose("codex")

            assertTrue(result.isSuccess)
            assertEquals("codex", result.getOrNull()?.provider)
            assertEquals(false, result.getOrNull()?.ok)
            assertTrue(result.getOrNull()?.message?.contains("Connection refused") ?: false)
        }

    @Test
    fun `refresh providers returns acknowledged status`() =
        runBlocking {
            val payload =
                buildJsonObject {
                    put("acknowledged", true)
                }

            coEvery { mockRpc.call("refresh_providers_snapshot_request") } returns Result.success(payload)

            val admin = PaseoDaemonAdmin(mockRpc)
            val result = admin.refreshProviders()

            assertTrue(result.isSuccess)
            assertEquals(true, result.getOrNull())
        }

    @Test
    fun `restart server succeeds`() =
        runBlocking {
            coEvery { mockRpc.call("restart_server_request") } returns Result.success(buildJsonObject {})

            val admin = PaseoDaemonAdmin(mockRpc)
            val result = admin.restart()

            assertTrue(result.isSuccess)
        }

    @Test
    fun `config handles rpc failure`() =
        runBlocking {
            coEvery { mockRpc.call("get_daemon_config_request") } returns
                Result.failure(IllegalStateException("Server offline"))

            val admin = PaseoDaemonAdmin(mockRpc)
            val result = admin.config()

            assertTrue(result.isFailure)
            assertEquals("Server offline", result.exceptionOrNull()?.message)
        }

    /** The daemon's own answers, recorded from a real daemon (sample-comprehensive.jsonl). */
    private fun recorded(type: String): JsonObject {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("paseo/sample-comprehensive.jsonl"))
        val line =
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.first { it.contains("\"type\": \"$type\"") }
            }
        val frame = checkNotNull(Json.parseToJsonElement(line).jsonObject["frame"]).jsonObject
        return checkNotNull(checkNotNull(frame["message"]).jsonObject["payload"]).jsonObject
    }

    @Test
    fun `a real daemon config reads the relay as on`() =
        runBlocking {
            coEvery { mockRpc.call("get_daemon_config_request", any()) } returns
                Result.success(recorded("get_daemon_config_response"))
            assertEquals(true, PaseoDaemonAdmin(mockRpc).config().getOrThrow().relayEnabled)
        }

    @Test
    fun `a real providers snapshot lists the enabled providers by id`() =
        runBlocking {
            coEvery { mockRpc.call("get_providers_snapshot_request", any()) } returns
                Result.success(recorded("get_providers_snapshot_response"))
            val providers = PaseoDaemonAdmin(mockRpc).providers().getOrThrow().map { it.id }
            assertTrue("claude" in providers, providers.toString())
            assertTrue("codex" !in providers, "codex is disabled in that daemon's config")
        }
}
