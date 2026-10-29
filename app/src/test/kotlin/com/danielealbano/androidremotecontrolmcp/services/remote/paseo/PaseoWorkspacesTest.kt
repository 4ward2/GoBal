package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class PaseoWorkspacesTest {
    /**
     * Test parsing fetch_workspaces response with minimal workspace entries.
     * Schema: WorkspaceDescriptorPayloadSchema (messages.ts line 3967)
     */
    @Test
    fun `listResponse parses minimal workspace entries`() {
        val payload =
            buildJsonObject {
                putJsonArray("entries") {
                    addJsonObject {
                        put("id", "ws-001")
                        put("projectId", "proj-001")
                        put("projectDisplayName", "My Project")
                        put("projectRootPath", "/home/user/project")
                        put("name", "main")
                        put("status", "idle")
                        put("lifecycle", "active")
                        putJsonObject("gitRuntime") {
                            put("currentBranch", "main")
                        }
                        putJsonArray("scripts") { }
                    }
                }
                putJsonObject("pageInfo") {
                    put("nextCursor", null)
                    put("prevCursor", null)
                    put("hasMore", false)
                }
            }

        val response = PaseoWorkspaceNodes.listResponse(payload)

        assertEquals(1, response.entries.size)
        assertEquals("ws-001", response.entries[0].id)
        assertEquals("My Project", response.entries[0].projectDisplayName)
        assertTrue(response.pageInfo.nextCursor == null)
        assertEquals(false, response.pageInfo.hasMore)
    }

    /**
     * Test parsing multiple workspaces with scripts.
     * Schema: WorkspaceScriptPayloadSchema (messages.ts line 3888)
     */
    @Test
    fun `listResponse parses workspaces with scripts`() {
        val payload =
            buildJsonObject {
                putJsonArray("entries") {
                    addJsonObject {
                        put("id", "ws-002")
                        put("projectId", "proj-002")
                        put("projectDisplayName", "Agent Project")
                        put("projectRootPath", "/home/user/agent")
                        put("name", "dev")
                        put("title", "Development Workspace")
                        put("status", "running")
                        put("lifecycle", "active")
                        putJsonObject("gitRuntime") {
                            put("currentBranch", "dev")
                        }
                        putJsonArray("scripts") {
                            addJsonObject {
                                put("scriptName", "start-server")
                                put("type", "service")
                                put("hostname", "localhost")
                                put("port", 8000)
                                put("lifecycle", "running")
                                put("health", "healthy")
                            }
                            addJsonObject {
                                put("scriptName", "run-tests")
                                put("type", "script")
                                put("hostname", "localhost")
                                put("port", null)
                                put("lifecycle", "idle")
                                put("health", null)
                            }
                        }
                    }
                }
                putJsonObject("pageInfo") {
                    put("nextCursor", "cursor-123")
                    put("prevCursor", null)
                    put("hasMore", true)
                }
            }

        val response = PaseoWorkspaceNodes.listResponse(payload)

        assertEquals(1, response.entries.size)
        val ws = response.entries[0]
        assertEquals("ws-002", ws.id)
        assertEquals("Development Workspace", ws.title)
        assertEquals(2, ws.scripts.size)
        assertEquals("start-server", ws.scripts[0].scriptName)
        assertEquals(8000, ws.scripts[0].port)
        assertEquals("run-tests", ws.scripts[1].scriptName)
        assertEquals(true, response.pageInfo.hasMore)
        assertEquals("cursor-123", response.pageInfo.nextCursor)
    }

    /**
     * Test parsing workspace_update push with upsert kind.
     * Schema: workspace_update with kind='upsert' (messages.ts line 4201)
     */
    @Test
    fun `workspaceChange parses upsert update`() {
        val payload =
            buildJsonObject {
                put("kind", "upsert")
                putJsonObject("workspace") {
                    put("id", "ws-new")
                    put("projectId", "proj-new")
                    put("projectDisplayName", "New Project")
                    put("projectRootPath", "/home/user/new")
                    put("name", "feature")
                    put("status", "needs_input")
                    put("lifecycle", "active")
                    putJsonObject("gitRuntime") {
                        put("currentBranch", "feature/xyz")
                    }
                    putJsonArray("scripts") {}
                }
            }

        val change = PaseoWorkspaceNodes.workspaceChange(payload)

        assertInstanceOf<WorkspaceChange.Upserted>(change)
        assertEquals("ws-new", change.workspace.id)
        assertEquals("feature", change.workspace.name)
        assertEquals("needs_input", change.workspace.status)
    }

    /**
     * Test parsing workspace_update push with remove kind.
     * Schema: workspace_update with kind='remove' (messages.ts line 4201)
     */
    @Test
    fun `workspaceChange parses remove update`() {
        val payload =
            buildJsonObject {
                put("kind", "remove")
                put("id", "ws-deleted")
            }

        val change = PaseoWorkspaceNodes.workspaceChange(payload)

        assertInstanceOf<WorkspaceChange.Removed>(change)
        assertEquals("ws-deleted", change.workspaceId)
    }

    /**
     * Test parsing unknown change returns null.
     */
    @Test
    fun `workspaceChange returns null for unknown kind`() {
        val payload =
            buildJsonObject {
                put("kind", "unknown")
            }

        val change = PaseoWorkspaceNodes.workspaceChange(payload)

        assertEquals(null, change)
    }

    /**
     * Test workspace with optional title field.
     */
    @Test
    fun `workspace uses name when title is null`() {
        val payload =
            buildJsonObject {
                putJsonArray("entries") {
                    addJsonObject {
                        put("id", "ws-003")
                        put("projectId", "proj-003")
                        put("projectDisplayName", "Test Project")
                        put("projectRootPath", "/tmp/test")
                        put("name", "test-ws")
                        put("title", null)
                        put("status", "done")
                        put("lifecycle", "active")
                        putJsonObject("gitRuntime") {}
                        putJsonArray("scripts") {}
                    }
                }
                putJsonObject("pageInfo") {
                    put("nextCursor", null)
                    put("prevCursor", null)
                    put("hasMore", false)
                }
            }

        val response = PaseoWorkspaceNodes.listResponse(payload)

        assertEquals(1, response.entries.size)
        assertEquals(null, response.entries[0].title)
        assertEquals("test-ws", response.entries[0].name)
    }

    /**
     * Test empty workspaces list.
     */
    @Test
    fun `listResponse handles empty entries`() {
        val payload =
            buildJsonObject {
                putJsonArray("entries") { }
                putJsonObject("pageInfo") {
                    put("nextCursor", null)
                    put("prevCursor", null)
                    put("hasMore", false)
                }
            }

        val response = PaseoWorkspaceNodes.listResponse(payload)

        assertEquals(0, response.entries.size)
        assertEquals(false, response.pageInfo.hasMore)
    }
}
