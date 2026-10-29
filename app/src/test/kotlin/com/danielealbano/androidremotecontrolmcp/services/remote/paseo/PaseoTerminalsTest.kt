package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PaseoTerminalsTest {
    @Test
    fun `list terminals parses response correctly`() =
        runTest {
            // Per messages.ts line 6298: terminals list with partial cwd
            val payload =
                buildJsonObject {
                    putJsonArray("terminals") {
                        addJsonObject {
                            put("id", "term-1")
                            put("name", "shell")
                            put("cwd", "/home/user")
                            put("title", "bash")
                        }
                        addJsonObject {
                            put("id", "term-2")
                            put("name", "python")
                            put("cwd", "/tmp")
                        }
                    }
                }
            val rpc =
                mockk<PaseoRpc> {
                    coEvery { call("list_terminals_request", any()) } returns Result.success(payload)
                }
            val client = PaseoTerminals(rpc)

            val result = client.list("/home/user")
            assertTrue(result.isSuccess)
            val terminals = result.getOrNull()!!
            assertEquals(2, terminals.size)
            assertEquals("term-1", terminals[0].id)
            assertEquals("shell", terminals[0].name)
            assertEquals("/home/user", terminals[0].cwd)
            assertEquals("bash", terminals[0].title)
            assertEquals("python", terminals[1].name)
        }

    @Test
    fun `list terminals with empty response returns empty list`() =
        runTest {
            val payload =
                buildJsonObject {
                    putJsonArray("terminals") { }
                }
            val rpc =
                mockk<PaseoRpc> {
                    coEvery { call("list_terminals_request", any()) } returns Result.success(payload)
                }
            val client = PaseoTerminals(rpc)

            val result = client.list()
            assertTrue(result.isSuccess)
            assertEquals(0, result.getOrNull()!!.size)
        }

    @Test
    fun `create terminal parses response correctly`() =
        runTest {
            // Per messages.ts line 6315-6327: create_terminal_response
            val payload =
                buildJsonObject {
                    putJsonObject("terminal") {
                        put("id", "new-term")
                        put("name", "terminal")
                        put("cwd", "/home/user")
                        put("title", "bash")
                    }
                }
            val rpc =
                mockk<PaseoRpc> {
                    coEvery { call("create_terminal_request", any()) } returns Result.success(payload)
                }
            val client = PaseoTerminals(rpc)

            val result = client.create("/home/user", "terminal")
            assertTrue(result.isSuccess)
            val terminal = result.getOrNull()!!
            assertEquals("new-term", terminal.id)
            assertEquals("terminal", terminal.name)
            assertEquals("/home/user", terminal.cwd)
            assertEquals("bash", terminal.title)
        }

    @Test
    fun `create terminal fails on missing terminal in response`() =
        runTest {
            val payload = buildJsonObject { }
            val rpc =
                mockk<PaseoRpc> {
                    coEvery { call("create_terminal_request", any()) } returns Result.success(payload)
                }
            val client = PaseoTerminals(rpc)

            val result = client.create("/home/user")
            assertTrue(result.isFailure)
            assertNotNull(result.exceptionOrNull())
        }

    @Test
    fun `startPolling emits screen states from capture requests`() =
        runTest {
            // Per messages.ts line 6356-6369: capture_terminal_response
            val screenPayload =
                buildJsonObject {
                    put("terminalId", "term-1")
                    putJsonArray("lines") {
                        add(JsonPrimitive("user@machine:~$ "))
                        add(JsonPrimitive("output here"))
                    }
                    put("totalLines", 2)
                }
            val rpc =
                mockk<PaseoRpc> {
                    coEvery { call("capture_terminal_request", any()) } returns Result.success(screenPayload)
                }
            val client = PaseoTerminals(rpc)

            // The poll waits between captures; runTest skips that virtual time.
            val emitted = client.startPolling("term-1").take(2).toList()

            assertTrue(emitted.isNotEmpty())
            assertEquals(2, emitted[0].lines.size)
            assertEquals("user@machine:~$ ", emitted[0].lines[0])
            assertEquals(2, emitted[0].totalLines)
        }

    @Test
    fun `refreshScreen calls capture and returns current state`() =
        runTest {
            val screenPayload =
                buildJsonObject {
                    put("terminalId", "term-1")
                    putJsonArray("lines") {
                        add(JsonPrimitive("refresh test"))
                    }
                    put("totalLines", 1)
                }
            val rpc =
                mockk<PaseoRpc> {
                    coEvery { call("capture_terminal_request", any()) } returns Result.success(screenPayload)
                }
            val client = PaseoTerminals(rpc)

            val result = client.refreshScreen("term-1")
            assertTrue(result.isSuccess)
            val screen = result.getOrNull()!!
            assertEquals(1, screen.lines.size)
            assertEquals("refresh test", screen.lines[0])
        }

    @Test
    fun `send input text message succeeds`() =
        runTest {
            val rpc =
                mockk<PaseoRpc> {
                    coEvery { tell("terminal_input", any()) } returns true
                }
            val client = PaseoTerminals(rpc)

            val result = client.send("term-1", TerminalClientMessage.Input("ls\r"))
            assertTrue(result.isSuccess)
        }

    @Test
    fun `send resize message succeeds`() =
        runTest {
            val rpc =
                mockk<PaseoRpc> {
                    coEvery { tell("terminal_input", any()) } returns true
                }
            val client = PaseoTerminals(rpc)

            val result = client.send("term-1", TerminalClientMessage.Resize(24, 80, "update"))
            assertTrue(result.isSuccess)
        }

    @Test
    fun `kill terminal succeeds`() =
        runTest {
            val payload =
                buildJsonObject {
                    put("terminalId", "term-1")
                    put("success", true)
                }
            val rpc =
                mockk<PaseoRpc> {
                    coEvery { call("kill_terminal_request", any()) } returns Result.success(payload)
                }
            val client = PaseoTerminals(rpc)

            val result = client.kill("term-1")
            assertTrue(result.isSuccess)
        }

    @Test
    fun `kill terminal fails on false success flag`() =
        runTest {
            val payload =
                buildJsonObject {
                    put("terminalId", "term-1")
                    put("success", false)
                }
            val rpc =
                mockk<PaseoRpc> {
                    coEvery { call("kill_terminal_request", any()) } returns Result.success(payload)
                }
            val client = PaseoTerminals(rpc)

            val result = client.kill("term-1")
            assertTrue(result.isFailure)
        }

    @Test
    fun `terminal info handles null optional fields`() =
        runTest {
            val payload =
                buildJsonObject {
                    putJsonArray("terminals") {
                        addJsonObject {
                            put("id", "term-1")
                            put("name", "shell")
                            put("cwd", "/home")
                            // title is optional, omitted
                        }
                    }
                }
            val rpc =
                mockk<PaseoRpc> {
                    coEvery { call("list_terminals_request", any()) } returns Result.success(payload)
                }
            val client = PaseoTerminals(rpc)

            val result = client.list()
            assertTrue(result.isSuccess)
            val terminals = result.getOrNull()!!
            assertEquals("term-1", terminals[0].id)
            assertEquals(null, terminals[0].title)
        }
}
