package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object PaseoTimelineItems {
    fun parseUserMessage(item: JsonObject): TimelineEntry.UserMessage {
        val text = item.text("text")
        val messageId = item.text("messageId").ifEmpty { item.text("clientMessageId") }
        return TimelineEntry.UserMessage(text, messageId)
    }

    fun parseAssistantMessage(item: JsonObject): TimelineEntry.AssistantMessage =
        TimelineEntry.AssistantMessage(item.text("text"), item.text("messageId"))

    fun parseReasoning(item: JsonObject): TimelineEntry.Reasoning = TimelineEntry.Reasoning(item.text("text"))

    fun parseTodo(item: JsonObject): TimelineEntry.Todo {
        val items =
            item.list("items").mapNotNull { it as? JsonObject }.map { i ->
                TodoItem(
                    text = i.text("text"),
                    completed = i.flag("completed") ?: false,
                    id = i.text("id").ifEmpty { null },
                    status = i.text("status").ifEmpty { null },
                    activeForm = i.text("activeForm").ifEmpty { null },
                )
            }
        return TimelineEntry.Todo(items)
    }

    fun parseError(item: JsonObject): TimelineEntry.Error = TimelineEntry.Error(item.text("message"))

    fun parseNotification(item: JsonObject): TimelineEntry.Notification =
        TimelineEntry.Notification(item.text("level"), item.text("message"))

    fun parseCompaction(item: JsonObject): TimelineEntry.Compaction =
        TimelineEntry.Compaction(
            status = item.text("status"),
            trigger = item.text("trigger").ifEmpty { null },
            preTokens = (item["preTokens"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
        )

    fun parsePlugin(item: JsonObject): TimelineEntry.Plugin =
        TimelineEntry.Plugin(
            id = item.text("id"),
            pluginId = item.text("pluginId"),
            kind = item.text("kind"),
            version = (item["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
        )

    fun parseToolCall(item: JsonObject): TimelineEntry.ToolCall {
        val callId = item.text("callId")
        val name = item.text("name")
        val status = item.text("status")
        val detail = PaseoToolDetails.parseToolDetail(item.obj("detail"))
        val error = item.text("error").ifEmpty { null }
        return TimelineEntry.ToolCall(callId, name, status, detail, error)
    }
}
