package com.danielealbano.androidremotecontrolmcp.ui.viewmodels.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.CheckoutDiff
import com.danielealbano.androidremotecontrolmcp.services.remote.paseo.FileEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FileContent(
    val path: String,
    val text: String,
    val isBinary: Boolean,
    val isTruncated: Boolean,
    val truncatedLineCount: Int? = null,
)

data class FilesUiState(
    val isLoading: Boolean = false,
    val entries: List<FileEntry> = emptyList(),
    val error: String? = null,
    val currentPath: String = "",
    val openedFile: FileContent? = null,
)

data class DiffUiState(
    val isLoading: Boolean = false,
    val diff: CheckoutDiff? = null,
    val error: String? = null,
    val isWatching: Boolean = false,
)

@HiltViewModel
class FilesViewModel
    @Inject
    constructor(
        private val computers: RemoteComputers,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _filesState = MutableStateFlow(FilesUiState())
        val filesState: StateFlow<FilesUiState> = _filesState.asStateFlow()

        private val _diffState = MutableStateFlow(DiffUiState())
        val diffState: StateFlow<DiffUiState> = _diffState.asStateFlow()

        private var diffSubscriptionId: String? = null

        fun open(
            computerId: String,
            path: String = "",
            workspaceRoot: String = "",
        ) {
            viewModelScope.launch(ioDispatcher) {
                _filesState.value = FilesUiState(isLoading = true, currentPath = path)

                val rpc = computers.rpc(computerId)
                if (rpc == null) {
                    _filesState.value = FilesUiState(error = "电脑没有连接")
                    return@launch
                }

                val files =
                    com.danielealbano.androidremotecontrolmcp.services.remote.paseo
                        .PaseoFiles(rpc)
                val result = files.list(cwd = workspaceRoot, path = if (path.isNotEmpty()) path else null)

                result
                    .onSuccess { entries ->
                        _filesState.value = FilesUiState(entries = entries, currentPath = path)
                    }.onFailure { error ->
                        _filesState.value = FilesUiState(error = error.message ?: "无法读取文件列表")
                    }
            }
        }

        fun readFile(
            computerId: String,
            path: String,
            workspaceRoot: String = "",
        ) {
            viewModelScope.launch(ioDispatcher) {
                _filesState.value = _filesState.value.copy(isLoading = true, error = null)

                val rpc = computers.rpc(computerId)
                if (rpc == null) {
                    _filesState.value = _filesState.value.copy(error = "电脑没有连接", isLoading = false)
                    return@launch
                }

                val files =
                    com.danielealbano.androidremotecontrolmcp.services.remote.paseo
                        .PaseoFiles(rpc)
                val result = files.read(cwd = workspaceRoot, path = path)

                result
                    .onSuccess { content ->
                        val lineCount = content.text?.split("\n")?.size ?: 0
                        val displayContent =
                            when {
                                content.binary -> "这是二进制文件，不能在手机上预览"
                                content.truncated -> "只显示了前 $lineCount 行"
                                else -> content.text ?: ""
                            }
                        val fileContent =
                            FileContent(
                                path = path,
                                text = displayContent,
                                isBinary = content.binary,
                                isTruncated = content.truncated,
                                truncatedLineCount = if (content.truncated) lineCount else null,
                            )
                        _filesState.value =
                            _filesState.value.copy(
                                openedFile = fileContent,
                                isLoading = false,
                            )
                    }.onFailure { error ->
                        _filesState.value =
                            _filesState.value.copy(
                                error = error.message ?: "无法打开文件",
                                isLoading = false,
                            )
                    }
            }
        }

        fun closeFile() {
            _filesState.value = _filesState.value.copy(openedFile = null)
        }

        fun startWatchingDiff(
            computerId: String,
            cwd: String = "",
            compareMode: String = "uncommitted",
            baseRef: String? = null,
        ) {
            viewModelScope.launch(ioDispatcher) {
                _diffState.value = DiffUiState(isLoading = true)

                val rpc = computers.rpc(computerId)
                if (rpc == null) {
                    _diffState.value = DiffUiState(error = "电脑没有连接")
                    return@launch
                }

                val files =
                    com.danielealbano.androidremotecontrolmcp.services.remote.paseo
                        .PaseoFiles(rpc)
                val result = files.watchDiff(cwd = cwd, compareMode = compareMode, baseRef = baseRef)

                result
                    .onSuccess { (subscriptionId, diffFlow) ->
                        diffSubscriptionId = subscriptionId
                        _diffState.value = DiffUiState(isWatching = true, isLoading = false)

                        viewModelScope.launch(ioDispatcher) {
                            diffFlow.collect { diff ->
                                _diffState.value = _diffState.value.copy(diff = diff)
                            }
                        }
                    }.onFailure { error ->
                        _diffState.value = DiffUiState(error = error.message ?: "无法订阅改动")
                    }
            }
        }

        fun stopWatchingDiff(computerId: String) {
            val subscriptionId = diffSubscriptionId ?: return
            viewModelScope.launch(ioDispatcher) {
                val rpc = computers.rpc(computerId)
                if (rpc == null) return@launch

                val files =
                    com.danielealbano.androidremotecontrolmcp.services.remote.paseo
                        .PaseoFiles(rpc)
                files
                    .unwatchDiff(subscriptionId)
                    .onSuccess {
                        diffSubscriptionId = null
                        _diffState.value = DiffUiState(isWatching = false)
                    }.onFailure { error ->
                        _diffState.value = _diffState.value.copy(error = error.message ?: "未知错误")
                    }
            }
        }

        override fun onCleared() {
            super.onCleared()
            diffSubscriptionId = null
        }
    }
