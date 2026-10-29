package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object PaseoToolDetails {
    fun parseToolDetail(detail: JsonObject?): ToolCallDetail {
        if (detail == null) return ToolCallDetail.Unknown()
        val type = detail.text("type")
        return when (type) {
            "shell" -> {
                parseShellDetail(detail)
            }

            "read" -> {
                parseReadDetail(detail)
            }

            "edit" -> {
                parseEditDetail(detail)
            }

            "write" -> {
                parseWriteDetail(detail)
            }

            "search" -> {
                parseSearchDetail(detail)
            }

            "fetch" -> {
                parseFetchDetail(detail)
            }

            "sub_agent" -> {
                parseSubAgentDetail(detail)
            }

            "plain_text" -> {
                ToolCallDetail.PlainText(
                    label = detail.text("label").ifEmpty { null },
                    text = detail.text("text").ifEmpty { null },
                    icon = detail.text("icon").ifEmpty { null },
                )
            }

            "plan" -> {
                parsePlanDetail(detail)
            }

            "worktree_setup" -> {
                ToolCallDetail.WorktreeSetup(setup = detail.text("setup").ifEmpty { null })
            }

            else -> {
                ToolCallDetail.Unknown(input = detail.toString().ifEmpty { null })
            }
        }
    }

    private fun parseShellDetail(detail: JsonObject): ToolCallDetail.Shell =
        ToolCallDetail.Shell(
            command = detail.text("command"),
            cwd = detail.text("cwd").ifEmpty { null },
            output = detail.text("output").ifEmpty { null },
            exitCode = (detail["exitCode"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
        )

    private fun parseReadDetail(detail: JsonObject): ToolCallDetail.Read =
        ToolCallDetail.Read(
            filePath = detail.text("filePath"),
            content = detail.text("content").ifEmpty { null },
            offset = (detail["offset"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            limit = (detail["limit"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
        )

    private fun parseEditDetail(detail: JsonObject): ToolCallDetail.Edit =
        ToolCallDetail.Edit(
            filePath = detail.text("filePath"),
            oldString = detail.text("oldString").ifEmpty { null },
            newString = detail.text("newString").ifEmpty { null },
            unifiedDiff = detail.text("unifiedDiff").ifEmpty { null },
        )

    private fun parseWriteDetail(detail: JsonObject): ToolCallDetail.Write =
        ToolCallDetail.Write(
            filePath = detail.text("filePath"),
            content = detail.text("content").ifEmpty { null },
        )

    private fun parseSearchDetail(detail: JsonObject): ToolCallDetail.Search {
        val webResults =
            detail
                .list("webResults")
                .mapNotNull { it as? JsonObject }
                .map { w -> ToolCallDetail.WebResult(w.text("title"), w.text("url")) }
        return ToolCallDetail.Search(
            query = detail.text("query"),
            toolName = detail.text("toolName").ifEmpty { null },
            content = detail.text("content").ifEmpty { null },
            filePaths = detail.list("filePaths").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            webResults = webResults.ifEmpty { null },
            numFiles = (detail["numFiles"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            numMatches = (detail["numMatches"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
        )
    }

    private fun parseFetchDetail(detail: JsonObject): ToolCallDetail.Fetch =
        ToolCallDetail.Fetch(
            url = detail.text("url"),
            prompt = detail.text("prompt").ifEmpty { null },
            result = detail.text("result").ifEmpty { null },
            code = (detail["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
        )

    private fun parseSubAgentDetail(detail: JsonObject): ToolCallDetail.SubAgent =
        ToolCallDetail.SubAgent(
            subAgentType = detail.text("subAgentType").ifEmpty { null },
            description = detail.text("description").ifEmpty { null },
            childSessionId = detail.text("childSessionId").ifEmpty { null },
            log = detail.text("log"),
        )

    private fun parsePlanDetail(detail: JsonObject): ToolCallDetail.Plan {
        val text = detail.text("text")
        return ToolCallDetail.Plan(text = text)
    }
}
