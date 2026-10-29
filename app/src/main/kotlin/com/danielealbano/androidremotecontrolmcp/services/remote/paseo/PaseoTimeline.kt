package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val SUMMARY_TRUNCATE_LENGTH = 60
private const val DETAIL_TRUNCATE_LENGTH = 40

/** One entry in an agent's timeline, covering all item types from Paseo. */
sealed class TimelineEntry(
    /** One-line summary in Chinese. */
    open val summary: String,
) {
    data class UserMessage(
        val text: String,
        val messageId: String,
    ) : TimelineEntry("用户：${text.lineSequence().firstOrNull().orEmpty().take(SUMMARY_TRUNCATE_LENGTH)}")

    data class AssistantMessage(
        val text: String,
        val messageId: String,
    ) : TimelineEntry("助手：${text.lineSequence().firstOrNull().orEmpty().take(SUMMARY_TRUNCATE_LENGTH)}")

    data class Reasoning(
        val text: String,
    ) : TimelineEntry("思考：${text.lineSequence().firstOrNull().orEmpty().take(SUMMARY_TRUNCATE_LENGTH)}")

    data class ToolCall(
        val callId: String,
        val name: String,
        val status: String,
        val detail: ToolCallDetail,
        val error: String? = null,
    ) : TimelineEntry(detail.summary)

    data class Todo(
        val items: List<TodoItem>,
    ) : TimelineEntry("待办 ${items.count { it.completed }}/${items.size}")

    data class Error(
        val message: String,
    ) : TimelineEntry("错误：${message.take(SUMMARY_TRUNCATE_LENGTH)}")

    data class Notification(
        val level: String,
        val message: String,
    ) : TimelineEntry("${levelLabel(level)}：${message.take(SUMMARY_TRUNCATE_LENGTH)}")

    data class Compaction(
        val status: String,
        val trigger: String? = null,
        val preTokens: Int? = null,
    ) : TimelineEntry("消息合并（$status）")

    data class Plugin(
        val id: String,
        val pluginId: String,
        val kind: String,
        val version: Int,
    ) : TimelineEntry("插件：$kind")

    companion object {
        private fun levelLabel(level: String): String =
            when (level) {
                "info" -> "信息"
                "warning" -> "警告"
                "error" -> "错误"
                else -> level
            }
    }
}

sealed class ToolCallDetail(
    open val summary: String,
) {
    data class Shell(
        val command: String,
        val cwd: String? = null,
        val output: String? = null,
        val exitCode: Int? = null,
    ) : ToolCallDetail("运行命令：$command")

    data class Read(
        val filePath: String,
        val content: String? = null,
        val offset: Int? = null,
        val limit: Int? = null,
    ) : ToolCallDetail("读文件：$filePath")

    data class Edit(
        val filePath: String,
        val oldString: String? = null,
        val newString: String? = null,
        val unifiedDiff: String? = null,
    ) : ToolCallDetail("改文件：$filePath")

    data class Write(
        val filePath: String,
        val content: String? = null,
    ) : ToolCallDetail("写文件：$filePath")

    data class Search(
        val query: String,
        val toolName: String? = null,
        val content: String? = null,
        val filePaths: List<String>? = null,
        val webResults: List<WebResult>? = null,
        val numFiles: Int? = null,
        val numMatches: Int? = null,
    ) : ToolCallDetail("搜索：$query")

    data class WebResult(
        val title: String,
        val url: String,
    )

    data class Fetch(
        val url: String,
        val prompt: String? = null,
        val result: String? = null,
        val code: Int? = null,
    ) : ToolCallDetail("抓取：$url")

    data class SubAgent(
        val subAgentType: String? = null,
        val description: String? = null,
        val childSessionId: String? = null,
        val log: String,
    ) : ToolCallDetail("子代理：$subAgentType")

    data class PlainText(
        val label: String? = null,
        val text: String? = null,
        val icon: String? = null,
    ) : ToolCallDetail("${label.orEmpty()}${text?.take(DETAIL_TRUNCATE_LENGTH).orEmpty()}")

    data class Plan(
        val text: String,
    ) : ToolCallDetail("计划：${text.lineSequence().firstOrNull().orEmpty().take(DETAIL_TRUNCATE_LENGTH)}")

    data class WorktreeSetup(
        val type: String = "worktree_setup",
        val setup: String? = null,
    ) : ToolCallDetail("工作区设置")

    data class Unknown(
        val input: String? = null,
        val output: String? = null,
    ) : ToolCallDetail("未知工具")
}

data class TodoItem(
    val text: String,
    val completed: Boolean,
    val id: String? = null,
    val status: String? = null,
    val activeForm: String? = null,
)

internal object PaseoTimeline {
    fun parse(item: JsonObject): TimelineEntry? {
        val type = item.text("type")
        return when (type) {
            "user_message" -> PaseoTimelineItems.parseUserMessage(item)
            "assistant_message" -> PaseoTimelineItems.parseAssistantMessage(item)
            "reasoning" -> PaseoTimelineItems.parseReasoning(item)
            "tool_call" -> PaseoTimelineItems.parseToolCall(item)
            "todo" -> PaseoTimelineItems.parseTodo(item)
            "error" -> PaseoTimelineItems.parseError(item)
            "notification" -> PaseoTimelineItems.parseNotification(item)
            "compaction" -> PaseoTimelineItems.parseCompaction(item)
            "plugin" -> PaseoTimelineItems.parsePlugin(item)
            else -> null
        }
    }

    fun page(payload: JsonObject): List<TimelineEntry> {
        val entries =
            payload.list("entries").mapNotNull { entry ->
                (entry as? JsonObject)?.let { e ->
                    e.obj("item")?.let { item -> parse(item) }
                }
            }
        return fold(entries)
    }

    fun fold(entries: List<TimelineEntry>): List<TimelineEntry> {
        if (entries.isEmpty()) return emptyList()

        val result = mutableListOf<TimelineEntry>()
        var currentAssistantId: String? = null
        var currentAssistantText = StringBuilder()
        var currentTool: TimelineEntry.ToolCall? = null
        var currentToolId: String? = null

        for (entry in entries) {
            when {
                entry is TimelineEntry.AssistantMessage -> {
                    FoldHelpers
                        .processMergeAssistantMessage(
                            result,
                            entry,
                            currentAssistantId,
                            currentAssistantText,
                        ).also {
                            currentAssistantId = it.first
                            currentAssistantText = it.second
                        }
                }

                entry is TimelineEntry.ToolCall -> {
                    FoldHelpers.processMergeToolCall(result, entry, currentTool, currentToolId).also {
                        currentTool = it.first
                        currentToolId = it.second
                    }
                }

                else -> {
                    FoldHelpers
                        .flushPendingAndAddEntry(
                            result,
                            currentAssistantId,
                            currentAssistantText,
                            currentTool,
                            entry,
                        ).also {
                            currentAssistantId = null
                            currentAssistantText = StringBuilder()
                            currentTool = null
                            currentToolId = null
                        }
                }
            }
        }

        FoldHelpers.flushRemaining(result, currentAssistantId, currentAssistantText, currentTool)

        return result
    }

    private object FoldHelpers {
        fun processMergeAssistantMessage(
            result: MutableList<TimelineEntry>,
            entry: TimelineEntry.AssistantMessage,
            currentAssistantId: String?,
            currentAssistantText: StringBuilder,
        ): Pair<String?, StringBuilder> =
            if (currentAssistantId != entry.messageId) {
                if (currentAssistantId != null) {
                    result.add(
                        TimelineEntry.AssistantMessage(
                            currentAssistantText.toString(),
                            currentAssistantId,
                        ),
                    )
                }
                entry.messageId to StringBuilder(entry.text)
            } else {
                currentAssistantText.append("\n").append(entry.text)
                currentAssistantId to currentAssistantText
            }

        fun processMergeToolCall(
            result: MutableList<TimelineEntry>,
            entry: TimelineEntry.ToolCall,
            currentTool: TimelineEntry.ToolCall?,
            currentToolId: String?,
        ): Pair<TimelineEntry.ToolCall?, String?> =
            if (currentToolId != entry.callId) {
                if (currentTool != null) {
                    result.add(currentTool)
                }
                entry to entry.callId
            } else {
                entry to entry.callId
            }

        fun flushPendingAndAddEntry(
            result: MutableList<TimelineEntry>,
            currentAssistantId: String?,
            currentAssistantText: StringBuilder,
            currentTool: TimelineEntry.ToolCall?,
            entry: TimelineEntry,
        ) {
            if (currentAssistantId != null) {
                result.add(
                    TimelineEntry.AssistantMessage(
                        currentAssistantText.toString(),
                        currentAssistantId,
                    ),
                )
            }
            if (currentTool != null) {
                result.add(currentTool)
            }
            result.add(entry)
        }

        fun flushRemaining(
            result: MutableList<TimelineEntry>,
            currentAssistantId: String?,
            currentAssistantText: StringBuilder,
            currentTool: TimelineEntry.ToolCall?,
        ) {
            if (currentAssistantId != null) {
                result.add(
                    TimelineEntry.AssistantMessage(
                        currentAssistantText.toString(),
                        currentAssistantId,
                    ),
                )
            }
            if (currentTool != null) {
                result.add(currentTool)
            }
        }
    }
}
