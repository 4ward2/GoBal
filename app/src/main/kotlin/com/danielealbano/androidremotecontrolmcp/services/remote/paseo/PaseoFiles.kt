package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** File list entry (messages.ts FileExplorerEntrySchema line 2685). */
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: String,
)

/** File content response (messages.ts FileExplorerFileSchema line 2693). */
data class FileContent(
    val text: String?,
    val truncated: Boolean,
    val binary: Boolean,
)

/** Diff line in a hunk (messages.ts DiffLineSchema line 2659). */
data class DiffLine(
    val type: String, // "add", "remove", "context", "header"
    val content: String,
)

/** Hunk in a diff file (messages.ts DiffHunkSchema line 2665). */
data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
)

/** Changed file in a diff (messages.ts ParsedDiffFileSchema line 2673). */
data class DiffFile(
    val path: String,
    val oldPath: String?,
    val isNew: Boolean,
    val isDeleted: Boolean,
    val additions: Int,
    val deletions: Int,
    val hunks: List<DiffHunk>,
    val status: String?, // "ok", "too_large", "binary"
)

/** Checkout diff subscription (messages.ts CheckoutDiffSubscriptionPayloadSchema line 5353). */
data class CheckoutDiff(
    val files: List<DiffFile>,
    val error: String?,
    val diffTooLarge: Boolean,
)

private const val MAX_FILE_READ_BYTES = 5000 * 1024

/**
 * Paseo file explorer and checkout diff client.
 * Messages from messages.ts:
 * - file_explorer_request (2709)
 * - subscribe_checkout_diff_request (2153)
 * - unsubscribe_checkout_diff_request (2168)
 * - file_explorer_response (5920)
 * - subscribe_checkout_diff_response (5362)
 * - checkout_diff_update (5376)
 */
class PaseoFiles(
    private val rpc: PaseoRpc,
) {
    private val parsers = PaseoFilesParsers

    /**
     * List files in a directory.
     */
    suspend fun list(
        cwd: String,
        path: String? = null,
    ): Result<List<FileEntry>> =
        rpc
            .call(
                "file_explorer_request",
                buildJsonObject {
                    put("cwd", cwd)
                    if (path != null) put("path", path)
                    put("mode", "list")
                },
            ).mapCatching { payload ->
                parsers.parseFileList(payload)
            }

    /**
     * Read file content.
     */
    suspend fun read(
        cwd: String,
        path: String,
    ): Result<FileContent> =
        rpc
            .call(
                "file_explorer_request",
                buildJsonObject {
                    put("cwd", cwd)
                    put("path", path)
                    put("mode", "file")
                    put("maxBytes", MAX_FILE_READ_BYTES)
                },
            ).mapCatching { payload ->
                parsers.parseFileContent(payload)
            }

    /**
     * Watch checkout diff updates. Returns a Flow of CheckoutDiff.
     */
    suspend fun watchDiff(
        cwd: String,
        compareMode: String = "uncommitted",
        baseRef: String? = null,
    ): Result<Pair<String, Flow<CheckoutDiff>>> =
        rpc
            .call(
                "subscribe_checkout_diff_request",
                buildJsonObject {
                    put("cwd", cwd)
                    putJsonObject("compare") {
                        put("mode", compareMode)
                        if (baseRef != null) put("baseRef", baseRef)
                    }
                },
            ).mapCatching { payload ->
                val subscriptionId = parsers.parseSubscribeDiffResponse(payload)
                subscriptionId to diffStream(subscriptionId)
            }

    /**
     * Unsubscribe from diff updates.
     */
    suspend fun unwatchDiff(subscriptionId: String): Result<Unit> =
        rpc
            .tell(
                "unsubscribe_checkout_diff_request",
                buildJsonObject {
                    put("subscriptionId", subscriptionId)
                },
            ).let {
                if (it) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        IllegalStateException("Failed to unsubscribe from diff $subscriptionId"),
                    )
                }
            }

    /**
     * Stream of checkout diff updates (from rpc.pushes).
     */
    private fun diffStream(subscriptionId: String): Flow<CheckoutDiff> =
        rpc.pushes
            .filter { it.type == "checkout_diff_update" }
            .filter { frame ->
                frame.payload["subscriptionId"]?.jsonPrimitive?.contentOrNull == subscriptionId
            }.map { frame ->
                parsers.parseDiffUpdate(frame.payload)
            }
}

internal object PaseoFilesParsers {
    fun parseFileList(payload: JsonObject): List<FileEntry> {
        val error = payload["error"]?.jsonPrimitive?.contentOrNull
        if (!error.isNullOrEmpty()) {
            error("File list error: $error")
        }

        val directory = payload["directory"]?.jsonObject ?: return emptyList()
        val entries = directory["entries"]?.jsonArray ?: emptyList()

        return entries.mapNotNull { entryJson ->
            val obj = entryJson.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val size = obj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
            val modified = obj["modifiedAt"]?.jsonPrimitive?.contentOrNull ?: ""

            FileEntry(
                name = name,
                path = path,
                isDirectory = kind == "directory",
                size = size,
                modified = modified,
            )
        }
    }

    fun parseFileContent(payload: JsonObject): FileContent {
        val error = payload["error"]?.jsonPrimitive?.contentOrNull
        if (!error.isNullOrEmpty()) {
            error("File read error: $error")
        }

        val file =
            payload["file"]?.jsonObject
                ?: error("No file in response")
        val kind = file["kind"]?.jsonPrimitive?.contentOrNull ?: "text"
        val encoding = file["encoding"]?.jsonPrimitive?.contentOrNull ?: "utf-8"
        val content = file["content"]?.jsonPrimitive?.contentOrNull ?: ""

        val text =
            when (encoding) {
                "base64" -> null
                else -> content
            }

        val binary = kind == "binary" || kind == "image"
        val truncated = file["truncated"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

        return FileContent(
            text = text,
            truncated = truncated,
            binary = binary,
        )
    }

    fun parseSubscribeDiffResponse(payload: JsonObject): String {
        val error = payload["error"]?.jsonPrimitive?.contentOrNull
        if (!error.isNullOrEmpty()) {
            error("Subscribe diff failed: $error")
        }

        return payload["subscriptionId"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing subscriptionId")
    }

    fun parseDiffUpdate(payload: JsonObject): CheckoutDiff {
        val error = payload["error"]?.jsonPrimitive?.contentOrNull
        val files = parseFilesArray(payload["files"]?.jsonArray)
        val diffTooLarge =
            payload["diffTooLarge"]?.jsonPrimitive?.contentOrNull?.toBoolean()
                ?: false

        return CheckoutDiff(
            files = files,
            error = error,
            diffTooLarge = diffTooLarge,
        )
    }

    private fun parseFilesArray(filesArray: kotlinx.serialization.json.JsonArray?): List<DiffFile> {
        if (filesArray == null) return emptyList()

        return filesArray.mapNotNull { fileJson ->
            val obj = fileJson.jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val oldPath = obj["oldPath"]?.jsonPrimitive?.contentOrNull
            val isNew = obj["isNew"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            val isDeleted = obj["isDeleted"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            val additions = obj["additions"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val deletions = obj["deletions"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val status = obj["status"]?.jsonPrimitive?.contentOrNull
            val hunks = parseHunksArray(obj["hunks"]?.jsonArray)

            DiffFile(
                path = path,
                oldPath = oldPath,
                isNew = isNew,
                isDeleted = isDeleted,
                additions = additions,
                deletions = deletions,
                hunks = hunks,
                status = status,
            )
        }
    }

    private fun parseHunksArray(hunksArray: kotlinx.serialization.json.JsonArray?): List<DiffHunk> {
        if (hunksArray == null) return emptyList()

        return hunksArray.mapNotNull { hunkJson ->
            val obj = hunkJson.jsonObject
            val oldStart = obj["oldStart"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val oldCount = obj["oldCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val newStart = obj["newStart"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val newCount = obj["newCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val lines = parseLinesArray(obj["lines"]?.jsonArray)

            DiffHunk(
                oldStart = oldStart,
                oldCount = oldCount,
                newStart = newStart,
                newCount = newCount,
                lines = lines,
            )
        }
    }

    private fun parseLinesArray(linesArray: kotlinx.serialization.json.JsonArray?): List<DiffLine> {
        if (linesArray == null) return emptyList()

        return linesArray.mapNotNull { lineJson ->
            val obj = lineJson.jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""

            DiffLine(
                type = type,
                content = content,
            )
        }
    }
}
