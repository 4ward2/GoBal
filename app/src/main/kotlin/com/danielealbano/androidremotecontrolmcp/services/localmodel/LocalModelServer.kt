package com.danielealbano.androidremotecontrolmcp.services.localmodel

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the embedded llama.cpp server as a child process of the app. No root: the executable is
 * installed with the app as a native library, and apps may run their own native code.
 *
 * The server listens on loopback only, unloads the model after [ServerLaunch.SLEEP_IDLE_SECONDS]
 * of quiet and loads it again on the next request, so "wake" is mostly the server's own doing;
 * this class starts the process, keeps the app in the foreground while it runs, and stops it.
 */
@Singleton
class LocalModelServer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val files: LocalModelFiles,
    ) : LocalModelGate {
        private val _status = MutableStateFlow<LocalServerStatus>(LocalServerStatus.Stopped)
        val status: StateFlow<LocalServerStatus> = _status.asStateFlow()

        private val lock = Mutex()
        private var process: Process? = null

        private val libraryDir: String get() = context.applicationInfo.nativeLibraryDir
        private val executable: File get() = File(libraryDir, ServerLaunch.EXECUTABLE)

        val supported: Boolean get() = executable.isFile

        override fun installed(modelId: String): Boolean {
            val spec = files.store.find(modelId)
            return spec != null && files.installed(spec)
        }

        override fun spec(modelId: String): LocalModelSpec? = files.store.find(modelId)

        override suspend fun wake(modelId: String): String? {
            val spec = files.store.find(modelId) ?: files.installedModels().firstOrNull()
            return if (spec == null) "还没有下载本机模型" else start(spec)
        }

        /**
         * Starts [spec] (stopping another model first) and waits until it serves. Safe to call
         * again while running: it returns at once. Null when ready, otherwise the reason.
         */
        suspend fun start(spec: LocalModelSpec): String? =
            lock.withLock {
                val current = _status.value
                val alive = process?.isAlive == true
                val serving = alive && current is LocalServerStatus.Running && current.modelId == spec.id
                if (serving) return@withLock null
                stopLocked()
                val problem =
                    preflight(spec) ?: run {
                        _status.value = LocalServerStatus.Starting(spec.id)
                        LocalModelService.start(context)
                        withContext(Dispatchers.IO) { launch(spec) }
                    }
                if (problem == null) {
                    _status.value = LocalServerStatus.Running(spec.id)
                } else {
                    stopLocked()
                    _status.value = LocalServerStatus.Failed(problem)
                }
                problem
            }

        suspend fun stop() = lock.withLock { stopLocked() }

        /** The phone's installed memory, to tell before a download that a model cannot run here. */
        fun totalMemory(): Long = context.memoryInfo().totalMem

        private fun preflight(spec: LocalModelSpec): String? =
            when {
                !supported -> "这台设备不支持本机模型（需要 64 位 ARM 处理器）"
                !files.installed(spec) -> "「${spec.name}」还没有下载完"
                else -> ServerLaunch.memoryShortfall(spec, context.memoryInfo().availMem)
            }

        /** Spawns the server and waits for it to answer; the reason in words when it does not. */
        private suspend fun launch(spec: LocalModelSpec): String? {
            ServerProcess.killLeftovers(executable.path)
            val log = File(context.filesDir, "localmodel/server.log").apply { parentFile?.mkdirs() }
            val threads = ServerLaunch.threadsFor(Runtime.getRuntime().availableProcessors())
            val command = ServerLaunch.command(executable, spec, files.dir(spec), threads)
            val spawned =
                runCatching {
                    ProcessBuilder(command)
                        .directory(context.filesDir)
                        .redirectErrorStream(true)
                        .redirectOutput(log)
                        .apply { environment()["LD_LIBRARY_PATH"] = libraryDir }
                        .start()
                }
            return spawned.fold(
                onSuccess = { started ->
                    process = started
                    awaitReady(started, log)
                },
                onFailure = { "本机模型启动失败：${it.message}" },
            )
        }

        private suspend fun awaitReady(
            started: Process,
            log: File,
        ): String? {
            val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && started.isAlive && !ServerProcess.healthy()) delay(POLL_MS)
            return when {
                ServerProcess.healthy() -> null
                !started.isAlive -> "本机模型启动失败：${ServerProcess.lastLines(log)}"
                else -> "本机模型加载超时"
            }
        }

        private fun stopLocked() {
            process?.let {
                it.destroy()
                runCatching { it.waitFor() }
            }
            process = null
            _status.value = LocalServerStatus.Stopped
            LocalModelService.stop(context)
        }

        private companion object {
            const val READY_TIMEOUT_MS = 240_000L
            const val POLL_MS = 500L
        }
    }

private fun Context.memoryInfo(): ActivityManager.MemoryInfo =
    ActivityManager.MemoryInfo().also { info -> getSystemService(ActivityManager::class.java)?.getMemoryInfo(info) }
