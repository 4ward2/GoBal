package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** An agent snapshot (AgentSnapshotPayload, messages.ts) as the daemon sends it. */
class PaseoAgentNodeTest {
    private fun agent(updatedAt: String) =
        Json
            .parseToJsonElement(
                """{"id":"a1","title":"t","provider":"claude","model":"m","cwd":"C:/w","status":"idle",
                   "updatedAt":"$updatedAt","labels":{}}""",
            ).jsonObject

    @Test
    fun `the last activity time is read in epoch milliseconds`() {
        assertEquals(1_792_395_543_066L, PaseoNodes.agent(agent("2026-10-19T07:39:03.066Z")).updatedAt)
    }

    @Test
    fun `a missing or unreadable time is zero, not now`() {
        assertEquals(0L, PaseoNodes.agent(agent("not a time")).updatedAt)
    }
}
