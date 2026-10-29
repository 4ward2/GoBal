package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import com.danielealbano.androidremotecontrolmcp.services.remote.PermissionReply
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteChoice
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The answers an agent offers to its own permission request, and the reply naming the one tapped. */
class PermissionChoicesTest {
    // AgentPermissionRequestPayloadSchema / AgentPermissionActionSchema, messages.ts ~460-490.
    private val request =
        Json
            .parseToJsonElement(
                """
                {"id":"permission-1","provider":"claude","name":"Bash","kind":"tool","title":"Run a command",
                 "input":{"command":"rm -rf build"},
                 "actions":[
                   {"id":"allow-once","label":"允许这一次","behavior":"allow","variant":"primary"},
                   {"id":"allow-always","label":"以后这个目录都允许","behavior":"allow"},
                   {"id":"deny","label":"不允许","behavior":"deny","variant":"danger"},
                   {"id":"","label":"no id","behavior":"allow"},
                   {"id":"odd","label":"unknown behaviour","behavior":"maybe"}
                 ]}
                """.trimIndent(),
            ).jsonObject

    private fun message(frame: String): JsonObject {
        val wrapper = Json.parseToJsonElement(frame).jsonObject
        return checkNotNull(wrapper["message"]).jsonObject
    }

    @Test
    fun `the agent's own answers are kept in order and malformed ones dropped`() {
        val asked = PaseoNodes.asked("a1", request) as RemoteEvent.PermissionAsked
        assertEquals(
            listOf(
                RemoteChoice("allow-once", "允许这一次", allow = true),
                RemoteChoice("allow-always", "以后这个目录都允许", allow = true),
                RemoteChoice("deny", "不允许", allow = false),
            ),
            asked.permission.choices,
        )
        assertTrue(asked.permission.detail.contains("rm -rf build"))
    }

    @Test
    fun `a request without answers leaves the plain allow and deny`() {
        val bare = JsonObject(request.filterKeys { it != "actions" })
        val asked = PaseoNodes.asked("a1", bare) as RemoteEvent.PermissionAsked
        assertTrue(asked.permission.choices.isEmpty())
    }

    @Test
    fun `the reply names the tapped answer`() {
        val reply = PermissionReply(allow = true, choiceId = "allow-always", message = "ignored when allowing")
        val response = checkNotNull(message(PaseoRequests.answer("a1", "permission-1", reply))["response"]).jsonObject
        assertEquals("allow", response.text("behavior"))
        assertEquals("allow-always", response.text("selectedActionId"))
        assertFalse(response.containsKey("message"), "only a refusal carries words for the agent")
    }

    @Test
    fun `a refusal carries the user's reason, a blank one is left out`() {
        val said = message(PaseoRequests.answer("a1", "p", PermissionReply(allow = false, message = "先跟我说打算")))
        val response = checkNotNull(said["response"]).jsonObject
        assertEquals("deny", response.text("behavior"))
        assertEquals("先跟我说打算", response.text("message"))
        assertFalse(response.containsKey("selectedActionId"))
        val blank = message(PaseoRequests.answer("a1", "p", PermissionReply(allow = false, message = "  ")))
        assertFalse(checkNotNull(blank["response"]).jsonObject.containsKey("message"))
    }
}
