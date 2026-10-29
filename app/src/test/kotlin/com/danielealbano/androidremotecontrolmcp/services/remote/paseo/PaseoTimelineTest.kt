package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class PaseoTimelineTest {
    @Test
    fun `parse user message`() {
        val json =
            buildJsonObject {
                put("type", "user_message")
                put("text", "What is 2+2?")
                put("messageId", "msg-123")
            }

        val result = PaseoTimeline.parse(json)

        assertInstanceOf<TimelineEntry.UserMessage>(result)
        assertEquals("What is 2+2?", result.text)
        assertEquals("msg-123", result.messageId)
    }

    @Test
    fun `parse user message with clientMessageId fallback`() {
        val json =
            buildJsonObject {
                put("type", "user_message")
                put("text", "Hello")
                put("clientMessageId", "client-456")
            }

        val result = PaseoTimeline.parse(json)

        assertInstanceOf<TimelineEntry.UserMessage>(result)
        assertEquals("client-456", result.messageId)
    }

    @Test
    fun `parse assistant message`() {
        val json =
            buildJsonObject {
                put("type", "assistant_message")
                put("text", "The answer is 4.")
                put("messageId", "msg-124")
            }

        val result = PaseoTimeline.parse(json)

        assertInstanceOf<TimelineEntry.AssistantMessage>(result)
        assertEquals("The answer is 4.", result.text)
        assertEquals("msg-124", result.messageId)
    }

    @Test
    fun `parse reasoning`() {
        val json =
            buildJsonObject {
                put("type", "reasoning")
                put("text", "I need to think about this problem...")
            }

        val result = PaseoTimeline.parse(json)

        assertInstanceOf<TimelineEntry.Reasoning>(result)
        assertEquals("I need to think about this problem...", result.text)
    }

    @Test
    fun `parse tool call with shell detail`() {
        val json =
            buildJsonObject {
                put("type", "tool_call")
                put("callId", "call-001")
                put("name", "bash")
                put("status", "completed")
                put(
                    "detail",
                    buildJsonObject {
                        put("type", "shell")
                        put("command", "ls -la")
                        put("cwd", "/home/user")
                        put("exitCode", 0)
                        put("output", "total 42\ndrwxr-xr-x  5 user user")
                    },
                )
            }

        val result = PaseoTimeline.parse(json)

        assertInstanceOf<TimelineEntry.ToolCall>(result)
        assertEquals("call-001", result.callId)
        assertEquals("bash", result.name)
        assertEquals("completed", result.status)
        assertInstanceOf<ToolCallDetail.Shell>(result.detail)
        assertEquals("ls -la", (result.detail as ToolCallDetail.Shell).command)
    }

    @Test
    fun `parse tool call with read detail`() {
        val json =
            buildJsonObject {
                put("type", "tool_call")
                put("callId", "call-002")
                put("name", "read_file")
                put("status", "running")
                put(
                    "detail",
                    buildJsonObject {
                        put("type", "read")
                        put("filePath", "/etc/hosts")
                        put("content", "127.0.0.1 localhost")
                    },
                )
            }

        val result = PaseoTimeline.parse(json)

        assertInstanceOf<TimelineEntry.ToolCall>(result)
        assertInstanceOf<ToolCallDetail.Read>(result.detail)
        assertEquals("/etc/hosts", (result.detail as ToolCallDetail.Read).filePath)
        assertEquals("running", result.status)
    }

    @Test
    fun `parse tool call with edit detail`() {
        val json =
            buildJsonObject {
                put("type", "tool_call")
                put("callId", "call-003")
                put("name", "edit_file")
                put("status", "failed")
                put(
                    "detail",
                    buildJsonObject {
                        put("type", "edit")
                        put("filePath", "test.txt")
                        put("oldString", "hello")
                        put("newString", "world")
                        put("unifiedDiff", "@@ -1 +1 @@\n-hello\n+world")
                    },
                )
                put("error", "Permission denied")
            }

        val result = PaseoTimeline.parse(json)

        assertInstanceOf<TimelineEntry.ToolCall>(result)
        assertInstanceOf<ToolCallDetail.Edit>(result.detail)
        assertEquals("failed", result.status)
        assertEquals("Permission denied", result.error)
    }

    @Test
    fun `parse tool call with search detail`() {
        val json =
            buildJsonObject {
                put("type", "tool_call")
                put("callId", "call-004")
                put("name", "grep")
                put("status", "completed")
                put(
                    "detail",
                    buildJsonObject {
                        put("type", "search")
                        put("query", "TODO")
                        put("toolName", "grep")
                        put("numMatches", 3)
                        put("content", "Found 3 matches")
                    },
                )
            }

        val result = PaseoTimeline.parse(json)

        assertInstanceOf<TimelineEntry.ToolCall>(result)
        assertInstanceOf<ToolCallDetail.Search>(result.detail)
        assertEquals("TODO", (result.detail as ToolCallDetail.Search).query)
    }

    @Test
    fun `parse todo`() {
        val json =
            buildJsonObject {
                put("type", "todo")
                putJsonArray("items") {
                    addJsonObject {
                        put("text", "Implement feature A")
                        put("completed", true)
                        put("id", "todo-1")
                    }
                    addJsonObject {
                        put("text", "Write tests")
                        put("completed", false)
                    }
                }
            }

        val result = PaseoTimeline.parse(json)

        assertInstanceOf<TimelineEntry.Todo>(result)
        assertEquals(2, result.items.size)
        assertTrue(result.items[0].completed)
        assertEquals("Implement feature A", result.items[0].text)
    }

    @Test
    fun `parse error`() {
        val json =
            buildJsonObject {
                put("type", "error")
                put("message", "Something went wrong")
            }

        val result = PaseoTimeline.parse(json)

        assertInstanceOf<TimelineEntry.Error>(result)
        assertEquals("Something went wrong", result.message)
    }

    @Test
    fun `parse notification`() {
        val json =
            buildJsonObject {
                put("type", "notification")
                put("level", "warning")
                put("message", "This is a warning")
            }

        val result = PaseoTimeline.parse(json)

        assertInstanceOf<TimelineEntry.Notification>(result)
        assertEquals("warning", result.level)
        assertEquals("This is a warning", result.message)
    }

    @Test
    fun `parse compaction`() {
        val json =
            buildJsonObject {
                put("type", "compaction")
                put("status", "completed")
                put("trigger", "auto")
                put("preTokens", 1000)
            }

        val result = PaseoTimeline.parse(json)

        assertInstanceOf<TimelineEntry.Compaction>(result)
        assertEquals("completed", result.status)
        assertEquals("auto", result.trigger)
        assertEquals(1000, result.preTokens)
    }

    @Test
    fun `parse plugin`() {
        val json =
            buildJsonObject {
                put("type", "plugin")
                put("id", "plugin-001")
                put("pluginId", "com.example.plugin")
                put("kind", "extension")
                put("version", 1)
            }

        val result = PaseoTimeline.parse(json)

        assertInstanceOf<TimelineEntry.Plugin>(result)
        assertEquals("plugin-001", result.id)
        assertEquals("com.example.plugin", result.pluginId)
    }

    @Test
    fun `fold concatenates assistant messages with same messageId`() {
        val entry1 = TimelineEntry.AssistantMessage("Hello", "msg-1")
        val entry2 = TimelineEntry.AssistantMessage(" world", "msg-1")
        val entry3 = TimelineEntry.Error("Error happened")

        val result = PaseoTimeline.fold(listOf(entry1, entry2, entry3))

        assertEquals(2, result.size)
        val first = assertInstanceOf<TimelineEntry.AssistantMessage>(result[0])
        assertEquals("Hello\n world", first.text)
        assertInstanceOf<TimelineEntry.Error>(result[1])
    }

    @Test
    fun `fold updates tool call with latest status`() {
        val entry1 =
            TimelineEntry.ToolCall(
                callId = "call-1",
                name = "bash",
                status = "running",
                detail = ToolCallDetail.Shell("ls"),
            )
        val entry2 =
            TimelineEntry.ToolCall(
                callId = "call-1",
                name = "bash",
                status = "completed",
                detail = ToolCallDetail.Shell("ls", output = "file.txt"),
            )

        val result = PaseoTimeline.fold(listOf(entry1, entry2))

        assertEquals(1, result.size)
        val entry = assertInstanceOf<TimelineEntry.ToolCall>(result[0])
        assertEquals("completed", entry.status)
    }

    @Test
    fun `page parses timeline response entries`() {
        val payload =
            buildJsonObject {
                putJsonArray("entries") {
                    addJsonObject {
                        put(
                            "item",
                            buildJsonObject {
                                put("type", "user_message")
                                put("text", "Hi")
                                put("messageId", "msg-1")
                            },
                        )
                    }
                    addJsonObject {
                        put(
                            "item",
                            buildJsonObject {
                                put("type", "assistant_message")
                                put("text", "Hello")
                                put("messageId", "msg-2")
                            },
                        )
                    }
                }
            }

        val result = PaseoTimeline.page(payload)

        assertEquals(2, result.size)
        assertInstanceOf<TimelineEntry.UserMessage>(result[0])
        assertInstanceOf<TimelineEntry.AssistantMessage>(result[1])
    }

    @Test
    fun `page returns empty list for empty entries`() {
        val payload =
            buildJsonObject {
                putJsonArray("entries") {}
            }

        val result = PaseoTimeline.page(payload)

        assertEquals(0, result.size)
    }

    @Test
    fun `parse unknown tool detail`() {
        val json =
            buildJsonObject {
                put("type", "tool_call")
                put("callId", "call-999")
                put("name", "unknown_tool")
                put("status", "completed")
                put(
                    "detail",
                    buildJsonObject {
                        put("type", "unknown_type")
                    },
                )
            }

        val result = PaseoTimeline.parse(json)

        assertInstanceOf<TimelineEntry.ToolCall>(result)
        assertInstanceOf<ToolCallDetail.Unknown>(result.detail)
    }

    @Test
    fun `parse worktree setup detail`() {
        val json =
            buildJsonObject {
                put("type", "tool_call")
                put("callId", "call-005")
                put("name", "worktree_setup")
                put("status", "completed")
                put(
                    "detail",
                    buildJsonObject {
                        put("type", "worktree_setup")
                        put("setup", "Creating worktree...")
                    },
                )
            }

        val result = PaseoTimeline.parse(json)

        assertInstanceOf<TimelineEntry.ToolCall>(result)
        assertInstanceOf<ToolCallDetail.WorktreeSetup>(result.detail)
    }

    @Test
    fun `fold handles empty list`() {
        val result = PaseoTimeline.fold(emptyList())

        assertEquals(0, result.size)
    }

    @Test
    fun `summary generation for different entry types`() {
        assertEquals(
            "用户：What is 2+2?",
            TimelineEntry.UserMessage("What is 2+2?", "msg-1").summary,
        )
        assertEquals(
            "助手：The answer is 4.",
            TimelineEntry.AssistantMessage("The answer is 4.", "msg-2").summary,
        )
        assertEquals(
            "思考：I think...",
            TimelineEntry.Reasoning("I think...").summary,
        )
        assertEquals(
            "待办 1/2",
            TimelineEntry
                .Todo(
                    listOf(
                        TodoItem("Task 1", true),
                        TodoItem("Task 2", false),
                    ),
                ).summary,
        )
    }
}
