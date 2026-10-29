package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaseoAgentAdminTest {
    private val mockRpc = mockk<PaseoRpc>()

    @Test
    fun `details parses agent snapshot correctly`() =
        runBlocking {
            val payload =
                buildJsonObject {
                    put(
                        "agent",
                        buildJsonObject {
                            put("id", "agent-123")
                            put("title", "My Claude")
                            put("provider", "claude")
                            put("model", "claude-opus")
                            put("currentModeId", "balanced")
                            put("thinkingOptionId", null)
                            put("status", "idle")
                            put("cwd", "/home/user/projects")
                            put("archivedAt", null)
                            put("labels", buildJsonObject {})
                        },
                    )
                    put("error", null)
                }

            coEvery { mockRpc.call("fetch_agent_request", any()) } returns Result.success(payload)

            val admin = PaseoAgentAdmin(mockRpc)
            val result = admin.details("agent-123")

            assertTrue(result.isSuccess)
            val details = result.getOrNull()!!
            assertEquals("agent-123", details.id)
            assertEquals("My Claude", details.title)
            assertEquals("claude", details.provider)
            assertEquals("claude-opus", details.model)
            assertEquals("balanced", details.modeId)
            assertEquals(null, details.thinkingOptionId)
            assertEquals("idle", details.status)
            assertEquals("/home/user/projects", details.cwd)
            assertFalse(details.archived)
        }

    @Test
    fun `details handles archived agent`() =
        runBlocking {
            val payload =
                buildJsonObject {
                    put(
                        "agent",
                        buildJsonObject {
                            put("id", "agent-456")
                            put("title", "Old Agent")
                            put("provider", "codex")
                            put("model", "gpt-4")
                            put("currentModeId", null)
                            put("status", "archived")
                            put("cwd", "/tmp")
                            put("archivedAt", "2026-10-19T12:00:00Z")
                            put("labels", buildJsonObject {})
                        },
                    )
                    put("error", null)
                }

            coEvery { mockRpc.call("fetch_agent_request", any()) } returns Result.success(payload)

            val admin = PaseoAgentAdmin(mockRpc)
            val result = admin.details("agent-456")

            assertTrue(result.isSuccess)
            val details = result.getOrNull()!!
            assertTrue(details.archived)
        }

    @Test
    fun `rename sends update request`() =
        runBlocking {
            val payload =
                buildJsonObject {
                    put("requestId", "req-1")
                    put("agentId", "agent-789")
                    put("accepted", true)
                    put("error", null)
                }

            coEvery { mockRpc.call("update_agent_request", any()) } returns Result.success(payload)

            val admin = PaseoAgentAdmin(mockRpc)
            val result = admin.rename("agent-789", "New Name")

            assertTrue(result.isSuccess)
        }

    @Test
    fun `rename failure returns error`() =
        runBlocking {
            val payload =
                buildJsonObject {
                    put("requestId", "req-2")
                    put("agentId", "agent-999")
                    put("accepted", false)
                    put("error", "Agent not found")
                }

            coEvery { mockRpc.call("update_agent_request", any()) } returns Result.success(payload)

            val admin = PaseoAgentAdmin(mockRpc)
            val result = admin.rename("agent-999", "Bad Name")

            assertTrue(result.isFailure)
        }

    @Test
    fun `archive sends archive request`() =
        runBlocking {
            val payload =
                buildJsonObject {
                    put("requestId", "req-3")
                    put("agentId", "agent-arc")
                    put("accepted", true)
                    put("error", null)
                }

            coEvery { mockRpc.call("archive_agent_request", any()) } returns Result.success(payload)

            val admin = PaseoAgentAdmin(mockRpc)
            val result = admin.archive("agent-arc")

            assertTrue(result.isSuccess)
        }

    @Test
    fun `delete sends delete request`() =
        runBlocking {
            val payload =
                buildJsonObject {
                    put("requestId", "req-4")
                    put("agentId", "agent-del")
                    put("accepted", true)
                    put("error", null)
                }

            coEvery { mockRpc.call("delete_agent_request", any()) } returns Result.success(payload)

            val admin = PaseoAgentAdmin(mockRpc)
            val result = admin.delete("agent-del")

            assertTrue(result.isSuccess)
        }

    @Test
    fun `setMode sends mode request`() =
        runBlocking {
            val payload =
                buildJsonObject {
                    put("requestId", "req-5")
                    put("agentId", "agent-mode")
                    put("accepted", true)
                    put("error", null)
                }

            coEvery { mockRpc.call("set_agent_mode_request", any()) } returns Result.success(payload)

            val admin = PaseoAgentAdmin(mockRpc)
            val result = admin.setMode("agent-mode", "fast")

            assertTrue(result.isSuccess)
        }

    @Test
    fun `setModel sends model request`() =
        runBlocking {
            val payload =
                buildJsonObject {
                    put("requestId", "req-6")
                    put("agentId", "agent-model")
                    put("accepted", true)
                    put("error", null)
                }

            coEvery { mockRpc.call("set_agent_model_request", any()) } returns Result.success(payload)

            val admin = PaseoAgentAdmin(mockRpc)
            val result = admin.setModel("agent-model", "claude-sonnet")

            assertTrue(result.isSuccess)
        }

    @Test
    fun `setThinking sends thinking request`() =
        runBlocking {
            val payload =
                buildJsonObject {
                    put("requestId", "req-7")
                    put("agentId", "agent-think")
                    put("accepted", true)
                    put("error", null)
                }

            coEvery { mockRpc.call("set_agent_thinking_request", any()) } returns Result.success(payload)

            val admin = PaseoAgentAdmin(mockRpc)
            val result = admin.setThinking("agent-think", "deep")

            assertTrue(result.isSuccess)
        }

    @Test
    fun `refresh sends refresh request`() =
        runBlocking {
            val payload =
                buildJsonObject {
                    put("requestId", "req-8")
                    put("agentId", "agent-refresh")
                    put("accepted", true)
                    put("error", null)
                }

            coEvery { mockRpc.call("refresh_agent_request", any()) } returns Result.success(payload)

            val admin = PaseoAgentAdmin(mockRpc)
            val result = admin.refresh("agent-refresh")

            assertTrue(result.isSuccess)
        }

    @Test
    fun `parse keeps the handle`() =
        runBlocking {
            val persistenceHandle =
                buildJsonObject {
                    put("provider", "claude")
                    put("sessionId", "sess-123")
                    put("nativeHandle", "handle-xyz")
                }
            val payload =
                buildJsonObject {
                    put(
                        "agent",
                        buildJsonObject {
                            put("id", "agent-with-persistence")
                            put("title", "Resumable Agent")
                            put("provider", "claude")
                            put("model", "claude-opus")
                            put("currentModeId", null)
                            put("status", "idle")
                            put("cwd", "/home")
                            put("archivedAt", "2026-10-19T10:00:00Z")
                            put("labels", buildJsonObject {})
                            put("persistence", persistenceHandle)
                        },
                    )
                    put("error", null)
                }

            coEvery { mockRpc.call("fetch_agent_request", any()) } returns Result.success(payload)

            val admin = PaseoAgentAdmin(mockRpc)
            val result = admin.details("agent-with-persistence")

            assertTrue(result.isSuccess)
            val details = result.getOrNull()!!
            assertEquals(persistenceHandle, details.persistence)
        }

    @Test
    fun `resume sends handle unchanged`() =
        runBlocking {
            val persistenceHandle =
                buildJsonObject {
                    put("provider", "claude")
                    put("sessionId", "sess-456")
                }
            val payload =
                buildJsonObject {
                    put("requestId", "req-resume")
                    put("accepted", true)
                    put("error", null)
                }

            coEvery { mockRpc.call("resume_agent_request", any()) } returns Result.success(payload)

            val admin = PaseoAgentAdmin(mockRpc)
            val result = admin.resume(persistenceHandle)

            assertTrue(result.isSuccess)
        }

    @Test
    fun `resume without handle fails without calling rpc`() =
        runBlocking {
            val admin = PaseoAgentAdmin(mockRpc)
            val result = admin.resume(null)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertEquals("这个代理没有可恢复的记录", error?.message)
        }
}
