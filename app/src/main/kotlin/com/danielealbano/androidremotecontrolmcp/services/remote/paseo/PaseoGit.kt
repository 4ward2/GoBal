package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import com.danielealbano.androidremotecontrolmcp.services.remote.REQUEST_TIMEOUT_MS
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val MAX_BRANCH_LIMIT = 200

/** Current branch info from git status. From messages.ts branch_suggestions_response line 5825 */
data class BranchInfo(
    val name: String,
    val isCurrent: Boolean = false,
    val isRemote: Boolean = false,
    val committerDate: Long? = null,
)

/** Worktree info from paseo_worktree_list_response. From messages.ts line 5882 */
data class WorktreeInfo(
    val path: String,
    val branch: String? = null,
    val head: String? = null,
    val createdAt: String? = null,
)

/** Where a workspace's checkout stands (checkout_status_response): its branch, and what is not yet shared. */
data class GitStatus(
    val branch: String?,
    val dirty: Boolean,
    val ahead: Int?,
)

/** Push result after git push. */
data class PushResult(
    val success: Boolean,
    val error: String? = null,
)

/** Parsing for branch and worktree related responses. */
internal object PaseoGitNodes {
    fun branchInfo(
        node: JsonObject,
        currentBranch: String? = null,
    ): BranchInfo {
        val name = text(node, "name")
        return BranchInfo(
            name = name,
            isCurrent = name == currentBranch,
            // Only on the remote: BranchSuggestionsResponseSchema has hasLocal/hasRemote, no isRemote.
            isRemote = node["hasLocal"]?.jsonPrimitive?.contentOrNull == "false",
            committerDate = node["committerDate"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        )
    }

    fun worktreeInfo(node: JsonObject): WorktreeInfo =
        WorktreeInfo(
            path = text(node, "worktreePath"),
            branch = node["branchName"]?.jsonPrimitive?.contentOrNull,
            head = node["head"]?.jsonPrimitive?.contentOrNull,
            createdAt = node["createdAt"]?.jsonPrimitive?.contentOrNull,
        )

    fun branchSuggestions(payload: JsonObject): List<BranchInfo> {
        val branches =
            payload["branchDetails"]
                ?.jsonArray
                ?.mapNotNull { detail ->
                    (detail as? JsonObject)?.let { branchInfo(it) }
                }.orEmpty()
        return branches
    }

    fun worktrees(payload: JsonObject): List<WorktreeInfo> =
        payload["worktrees"]
            ?.jsonArray
            ?.mapNotNull { wt ->
                (wt as? JsonObject)?.let(::worktreeInfo)
            }.orEmpty()

    fun pushResult(payload: JsonObject): PushResult =
        PushResult(
            success = payload["success"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
            error =
                payload["error"]?.jsonObject?.let { error ->
                    text(error, "message").takeIf { it.isNotEmpty() }
                },
        )

    fun validateBranchResponse(payload: JsonObject): Pair<Boolean, String?> {
        val exists = payload["exists"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        val resolvedRef = payload["resolvedRef"]?.jsonPrimitive?.contentOrNull
        return Pair(exists, resolvedRef)
    }

    private fun text(
        node: JsonObject,
        key: String,
    ): String = node[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}

/**
 * Client for Git branch and worktree operations in Paseo.
 * Messages from messages.ts:
 * - branch_suggestions_request (2387)
 * - validate_branch_request (2342)
 * - checkout_switch_branch_request (2348)
 * - checkout_push_request (2205)
 * - paseo_worktree_list_request (2458)
 * - paseo_worktree_archive_request (2465)
 * - create_paseo_worktree_request (2494)
 * - branch_suggestions_response (5815)
 * - validate_branch_response (5804)
 * - checkout_switch_branch_response (5743)
 * - checkout_push_response (5422)
 * - paseo_worktree_list_response (5894)
 * - paseo_worktree_archive_response (5907)
 * - create_paseo_worktree_response (5920)
 */
class PaseoGit(
    private val rpc: PaseoRpc,
) {
    /**
     * List branch suggestions for a workspace, optionally filtered by a query.
     */
    suspend fun branches(
        cwd: String,
        query: String? = null,
        limit: Int = 100,
    ): Result<List<BranchInfo>> =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            rpc
                .call(
                    "branch_suggestions_request",
                    buildJsonObject {
                        put("cwd", cwd)
                        if (query != null) put("query", query)
                        if (limit in 1..MAX_BRANCH_LIMIT) put("limit", limit)
                    },
                ).mapCatching { payload ->
                    PaseoGitNodes.branchSuggestions(payload)
                }
        } ?: Result.failure(IllegalStateException("Branch suggestions request timed out"))

    /**
     * Validate if a branch name exists and resolve its reference.
     */
    suspend fun validate(
        cwd: String,
        branchName: String,
    ): Result<Pair<Boolean, String?>> =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            rpc
                .call(
                    "validate_branch_request",
                    buildJsonObject {
                        put("cwd", cwd)
                        put("branchName", branchName)
                    },
                ).mapCatching { payload ->
                    PaseoGitNodes.validateBranchResponse(payload)
                }
        } ?: Result.failure(IllegalStateException("Validate branch request timed out"))

    /**
     * Switch to a different branch in a workspace.
     */
    suspend fun switchBranch(
        cwd: String,
        branchName: String,
    ): Result<String> =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            rpc
                .call(
                    "checkout_switch_branch_request",
                    buildJsonObject {
                        put("cwd", cwd)
                        put("branch", branchName)
                    },
                ).mapCatching { payload ->
                    // Return current branch name on success
                    payload["branch"]?.jsonPrimitive?.contentOrNull
                        ?: error("No branch in response")
                }
        } ?: Result.failure(IllegalStateException("Switch branch request timed out"))

    /**
     * Push commits to remote.
     */
    suspend fun push(cwd: String): Result<PushResult> =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            rpc
                .call(
                    "checkout_push_request",
                    buildJsonObject {
                        put("cwd", cwd)
                    },
                ).mapCatching { payload ->
                    PaseoGitNodes.pushResult(payload)
                }
        } ?: Result.failure(IllegalStateException("Push request timed out"))

    /** The checkout's branch, uncommitted changes and commits not yet on the remote; a folder outside git has none. */
    suspend fun status(cwd: String): Result<GitStatus> =
        rpc.call("checkout_status_request", buildJsonObject { put("cwd", cwd) }).mapCatching { payload ->
            GitStatus(
                branch = payload["currentBranch"]?.jsonPrimitive?.contentOrNull,
                dirty = payload["isDirty"]?.jsonPrimitive?.contentOrNull == "true",
                ahead = payload["aheadOfOrigin"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            )
        }

    /**
     * List Paseo-managed worktrees.
     */
    suspend fun worktrees(cwd: String? = null): Result<List<WorktreeInfo>> =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            rpc
                .call(
                    "paseo_worktree_list_request",
                    buildJsonObject {
                        if (cwd != null) put("cwd", cwd)
                    },
                ).mapCatching { payload ->
                    PaseoGitNodes.worktrees(payload)
                }
        } ?: Result.failure(IllegalStateException("Worktree list request timed out"))

    /**
     * Create a new worktree from a branch.
     */
    suspend fun createWorktree(
        cwd: String,
        branchName: String,
    ): Result<Unit> =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            rpc
                .call(
                    "create_paseo_worktree_request",
                    buildJsonObject {
                        put("cwd", cwd)
                        putJsonObject("worktree") {
                            put("mode", "checkout-branch")
                            put("branch", branchName)
                        }
                    },
                ).mapCatching { payload ->
                    val error = payload["error"]?.jsonPrimitive?.contentOrNull
                    if (error != null) {
                        throw IllegalStateException(error)
                    }
                }
        } ?: Result.failure(IllegalStateException("Create worktree request timed out"))

    /**
     * Archive a worktree (remove it from tracking).
     */
    suspend fun archiveWorktree(worktreePath: String): Result<Unit> =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            rpc
                .call(
                    "paseo_worktree_archive_request",
                    buildJsonObject {
                        put("worktreePath", worktreePath)
                        put("scope", "worktree")
                    },
                ).mapCatching { payload ->
                    val error =
                        payload["error"]?.jsonObject?.let { err ->
                            err["message"]?.jsonPrimitive?.contentOrNull
                        }
                    if (error != null) {
                        throw IllegalStateException(error)
                    }
                }
        } ?: Result.failure(IllegalStateException("Archive worktree request timed out"))
}
