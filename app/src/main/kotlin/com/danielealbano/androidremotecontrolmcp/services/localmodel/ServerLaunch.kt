package com.danielealbano.androidremotecontrolmcp.services.localmodel

import java.io.File

/** The decisions around starting the server, as pure functions so they can be tested off the phone. */
object ServerLaunch {
    /** Loopback only: the server has no authentication, so nothing off the phone may reach it. */
    const val HOST = "127.0.0.1"
    const val PORT = 8093
    const val BASE_URL = "http://$HOST:$PORT/v1"

    /** The executable is shipped as a library so the installer extracts it where apps may run code. */
    const val EXECUTABLE = "libllama-server-exec.so"

    /** Idle this long and the server unloads the model; the next request loads it again. */
    const val SLEEP_IDLE_SECONDS = 300

    private const val MIN_THREADS = 2
    private const val MAX_THREADS = 4

    /**
     * Command line for [spec] with its files in [dir]. The prompt cache is kept small (its default,
     * 8 GB, would not fit next to the weights on a phone) and a single slot serves one conversation.
     */
    fun command(
        executable: File,
        spec: LocalModelSpec,
        dir: File,
        threads: Int,
    ): List<String> {
        val args =
            mutableListOf(
                executable.path,
                "--model",
                File(dir, spec.weights.name).path,
                "--alias",
                spec.id,
                "--host",
                HOST,
                "--port",
                PORT.toString(),
                "--ctx-size",
                spec.contextSize.toString(),
                "--threads",
                threads.toString(),
                "--parallel",
                "1",
                "--cache-ram",
                "256",
                "--no-webui",
                "--sleep-idle-seconds",
                SLEEP_IDLE_SECONDS.toString(),
            )
        spec.mmproj?.let { args += listOf("--mmproj", File(dir, it.name).path) }
        return args
    }

    /**
     * Threads for generation: phones mix a few fast cores with slow ones, and spreading work onto
     * the slow ones makes every token wait for them. About half the cores, between 2 and 4.
     */
    fun threadsFor(cores: Int): Int = (cores / 2).coerceIn(MIN_THREADS, MAX_THREADS)

    /** Whether [available] bytes of memory leave room for [spec]; the reason in words when not. */
    fun memoryShortfall(
        spec: LocalModelSpec,
        available: Long,
    ): String? {
        if (available >= spec.ramBytes) return null
        return "内存不够：需要约 ${gib(spec.ramBytes)} GB，现在可用 ${gib(available)} GB。关掉几个后台应用再试。"
    }

    private fun gib(bytes: Long): String = "%.1f".format(bytes / BYTES_PER_GIB)

    private const val BYTES_PER_GIB = 1024.0 * 1024 * 1024
}
