package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import com.danielealbano.androidremotecontrolmcp.services.remote.REQUEST_TIMEOUT_MS
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Workspace script in Paseo. From messages.ts:3888 */
data class WorkspaceScript(
    val scriptName: String,
    val type: String = "service",
    val hostname: String,
    val port: Int?,
    val lifecycle: String,
    val health: String?,
    val exitCode: Int?,
)

/** Workspace in Paseo. From messages.ts:3967 */
data class WorkspaceInfo(
    val id: String,
    val projectId: String,
    val projectDisplayName: String,
    val projectRootPath: String,
    val workspaceDirectory: String,
    val name: String,
    val title: String?,
    val status: String,
    val scripts: List<WorkspaceScript>,
)

/** Response from fetch_workspaces. From messages.ts:4181 */
data class WorkspaceListResponse(
    val entries: List<WorkspaceInfo>,
    val pageInfo: PageInfo,
)

/** Pagination info from fetch_workspaces response. From messages.ts:4181 */
data class PageInfo(
    val nextCursor: String?,
    val prevCursor: String?,
    val hasMore: Boolean,
)

/** Change to workspace state; pushed via workspace_update. From messages.ts:4201 */
sealed class WorkspaceChange {
    data class Upserted(
        val workspace: WorkspaceInfo,
    ) : WorkspaceChange()

    data class Removed(
        val workspaceId: String,
    ) : WorkspaceChange()
}

/** Parses workspace data from Paseo protocol frames. */
internal object PaseoWorkspaceNodes {
    fun listResponse(payload: JsonObject): WorkspaceListResponse {
        val entries =
            payload["entries"]
                ?.jsonArray
                ?.mapNotNull { entry ->
                    (entry as? JsonObject)?.let(::workspace)
                }.orEmpty()
        val pageInfo = payload["pageInfo"]?.jsonObject?.let(::pageInfo) ?: PageInfo(null, null, false)
        return WorkspaceListResponse(entries, pageInfo)
    }

    fun workspaceChange(payload: JsonObject): WorkspaceChange? =
        when {
            payload["kind"]?.jsonPrimitive?.contentOrNull == "upsert" -> {
                payload["workspace"]?.jsonObject?.let { ws -> WorkspaceChange.Upserted(workspace(ws)) }
            }

            payload["kind"]?.jsonPrimitive?.contentOrNull == "remove" -> {
                payload["id"]?.jsonPrimitive?.contentOrNull?.let { WorkspaceChange.Removed(it) }
            }

            else -> {
                null
            }
        }

    private fun workspace(node: JsonObject): WorkspaceInfo {
        val scripts =
            node["scripts"]
                ?.jsonArray
                ?.mapNotNull { script ->
                    (script as? JsonObject)?.let(::script)
                }.orEmpty()
        return WorkspaceInfo(
            id = text(node, "id"),
            projectId = text(node, "projectId"),
            projectDisplayName = text(node, "projectDisplayName"),
            projectRootPath = text(node, "projectRootPath"),
            workspaceDirectory = text(node, "workspaceDirectory") ?: text(node, "projectRootPath"),
            name = text(node, "name"),
            title = node["title"]?.jsonPrimitive?.contentOrNull,
            status = text(node, "status"),
            scripts = scripts,
        )
    }

    private fun script(node: JsonObject): WorkspaceScript =
        WorkspaceScript(
            scriptName = text(node, "scriptName"),
            type = text(node, "type").ifEmpty { "service" },
            hostname = text(node, "hostname"),
            port = node["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            lifecycle = text(node, "lifecycle"),
            health = node["health"]?.jsonPrimitive?.contentOrNull,
            exitCode = node["exitCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        )

    private fun pageInfo(node: JsonObject): PageInfo =
        PageInfo(
            nextCursor = node["nextCursor"]?.jsonPrimitive?.contentOrNull,
            prevCursor = node["prevCursor"]?.jsonPrimitive?.contentOrNull,
            hasMore = node["hasMore"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
        )

    private fun text(
        node: JsonObject,
        key: String,
    ): String = node[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}

/** Client for Paseo workspaces API. */
class PaseoWorkspaces(
    private val rpc: PaseoRpc,
) {
    suspend fun list(): Result<WorkspaceListResponse> =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            rpc.call("fetch_workspaces_request").mapCatching { payload ->
                PaseoWorkspaceNodes.listResponse(payload)
            }
        } ?: Result.failure(Exception("Workspaces list request timed out"))

    suspend fun open(workspaceId: String): Result<Unit> =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            rpc.call("open_project_request", jsonObject { it["projectId"] = workspaceId }).map { }
        } ?: Result.failure(Exception("Open workspace request timed out"))

    suspend fun archive(workspaceId: String): Result<Unit> =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            rpc.call("archive_workspace_request", jsonObject { it["workspaceId"] = workspaceId }).map { }
        } ?: Result.failure(Exception("Archive workspace request timed out"))

    suspend fun setupStatus(workspaceId: String): Result<String> =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            rpc
                .call("workspace_setup_status_request", jsonObject { it["workspaceId"] = workspaceId })
                .mapCatching { payload -> payload["status"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        } ?: Result.failure(Exception("Setup status request timed out"))

    suspend fun startScript(
        workspaceId: String,
        scriptName: String,
    ): Result<Unit> =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            rpc
                .call(
                    "start_workspace_script_request",
                    jsonObject {
                        it["workspaceId"] = workspaceId
                        it["scriptName"] = scriptName
                    },
                ).map { }
        } ?: Result.failure(Exception("Start script request timed out"))

    fun updates() = rpc.pushes
}

private fun jsonObject(block: (MutableMap<String, Any?>) -> Unit): JsonObject {
    val map = mutableMapOf<String, Any?>()
    block(map)
    return JsonObject(
        map.mapValues { (_, v) ->
            when (v) {
                is String -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        },
    )
}
