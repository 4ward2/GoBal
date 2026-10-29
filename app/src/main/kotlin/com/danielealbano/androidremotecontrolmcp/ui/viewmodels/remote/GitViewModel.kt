package com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.BranchInfo
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.PaseoGit
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.WorktreeInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GitUiState(
    val currentBranch: String = "",
    /** Uncommitted changes and commits not yet pushed, said in a line; empty when there is nothing. */
    val pending: String = "",
    val branches: List<BranchInfo> = emptyList(),
    val worktrees: List<WorktreeInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val pushResult: String? = null,
)

/** Branches and worktrees of one workspace on a computer. Every change is the user's own tap. */
@HiltViewModel
class GitViewModel
    @Inject
    constructor(
        private val computers: RemoteComputers,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(GitUiState())
        val uiState: StateFlow<GitUiState> = mutableState.asStateFlow()

        private var computerId = ""
        private var cwd = ""

        fun open(
            computerId: String,
            cwd: String,
        ) {
            this.computerId = computerId
            this.cwd = cwd
            refresh()
        }

        fun refresh() =
            work(UNREADABLE) { git ->
                val status = git.status(cwd).getOrNull()
                val branches = git.branches(cwd).getOrThrow()
                val worktrees = git.worktrees(cwd).getOrDefault(emptyList())
                mutableState.update {
                    it.copy(
                        currentBranch = status?.branch ?: branches.firstOrNull { b -> b.isCurrent }?.name.orEmpty(),
                        pending = pendingLine(status?.dirty == true, status?.ahead ?: 0),
                        branches = branches,
                        worktrees = worktrees,
                    )
                }
            }

        fun searchBranches(query: String) =
            work(UNREADABLE) { git ->
                val branches = git.branches(cwd, query.ifBlank { null }).getOrThrow()
                mutableState.update { it.copy(branches = branches) }
            }

        fun switchBranch(branchName: String) =
            work(NOT_SWITCHED) { git ->
                git.switchBranch(cwd, branchName).getOrThrow()
                refreshNow(git)
            }

        fun push() =
            work(NOT_PUSHED) { git ->
                val result = git.push(cwd).getOrThrow()
                mutableState.update { it.copy(pushResult = if (result.success) PUSHED else NOT_PUSHED) }
                refreshNow(git)
            }

        fun createWorktree(branchName: String) =
            work(NOT_CREATED) { git ->
                git.createWorktree(cwd, branchName).getOrThrow()
                refreshNow(git)
            }

        fun archiveWorktree(worktreePath: String) =
            work(NOT_ARCHIVED) { git ->
                git.archiveWorktree(worktreePath).getOrThrow()
                refreshNow(git)
            }

        /** Puts away the error or the push result the user has read. */
        fun dismiss() = mutableState.update { it.copy(error = null, pushResult = null) }

        /** Runs [block] against the computer; any failure becomes [failure], said in Chinese. */
        private fun work(
            failure: String,
            block: suspend (PaseoGit) -> Unit,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val git = computers.rpc(computerId)?.let(::PaseoGit)
                mutableState.update { it.copy(isLoading = true, error = null) }
                val failed = git?.let { runCatching { block(it) }.isFailure } ?: true
                val error = if (git == null) NOT_CONNECTED else failure.takeIf { failed }
                mutableState.update { it.copy(isLoading = false, error = error) }
            }
        }

        private suspend fun refreshNow(git: PaseoGit) {
            val status = git.status(cwd).getOrNull() ?: return
            mutableState.update {
                it.copy(currentBranch = status.branch.orEmpty(), pending = pendingLine(status.dirty, status.ahead ?: 0))
            }
        }
    }

private const val NOT_CONNECTED = "电脑没有连接"
private const val UNREADABLE = "读不到这个工作区的分支"
private const val NOT_SWITCHED = "没能切换分支（可能有未提交的改动）"
private const val NOT_PUSHED = "没能推送"
private const val PUSHED = "已推送"
private const val NOT_CREATED = "没能新建工作树"
private const val NOT_ARCHIVED = "没能归档这个工作树"
private const val DIRTY = "有未提交的改动"
private const val AHEAD = "有 %d 个提交还没推送"

/** Uncommitted changes and unpushed commits in one line; empty when there is neither. */
private fun pendingLine(
    dirty: Boolean,
    ahead: Int,
): String = listOfNotNull(DIRTY.takeIf { dirty }, AHEAD.format(ahead).takeIf { ahead > 0 }).joinToString("，")
