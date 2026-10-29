package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.backup.BackupArchive
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class BackupUiState(
    val working: Boolean = false,
    /** What the last backup or restore came to, in the user's words. */
    val result: String? = null,
    /** True after a restore: the app has to be opened again to show what was put back. */
    val restored: Boolean = false,
)

/** Saves the user's content to a file they choose, and puts such a file back. */
@HiltViewModel
class BackupViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val archive = BackupArchive(context.filesDir)
        private val _ui = MutableStateFlow(BackupUiState())
        val ui: StateFlow<BackupUiState> = _ui.asStateFlow()

        fun exportTo(uri: Uri) =
            work { resolver ->
                val out = resolver.openOutputStream(uri) ?: throw IOException(CANNOT_OPEN)
                val count = out.use { archive.export(it) }
                BackupUiState(result = "已备份 $count 个文件")
            }

        fun importFrom(uri: Uri) =
            work { resolver ->
                val input = resolver.openInputStream(uri) ?: throw IOException(CANNOT_OPEN)
                val count = input.use { archive.import(it) }
                BackupUiState(result = "已恢复 $count 个文件。请关掉应用再打开，内容才会显示出来。", restored = true)
            }

        private fun work(job: (android.content.ContentResolver) -> BackupUiState) {
            if (_ui.value.working) return
            _ui.update { it.copy(working = true, result = null) }
            viewModelScope.launch(ioDispatcher) {
                val done = runCatching { job(context.contentResolver) }
                _ui.value = done.getOrElse { e -> BackupUiState(result = "没有成功：${e.message ?: CANNOT_OPEN}") }
            }
        }

        private companion object {
            const val CANNOT_OPEN = "打不开这个文件"
        }
    }
