package com.danielealbano.androidremotecontrolmcp.services.localmodel

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Small process chores around the embedded server: health, leftovers, the tail of its log. */
object ServerProcess {
    private const val TAG = "MCP:LocalModel"
    private const val HEALTH_TIMEOUT_MS = 1_500
    private const val HTTP_OK = 200
    private const val LOG_LINES = 3
    private const val LOG_CHARS = 300

    /** The server answers /health with 200 once the model is loaded (503 while loading). */
    fun healthy(): Boolean =
        runCatching {
            val url = URL("http://${ServerLaunch.HOST}:${ServerLaunch.PORT}/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = HEALTH_TIMEOUT_MS
            conn.readTimeout = HEALTH_TIMEOUT_MS
            try {
                conn.responseCode == HTTP_OK
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)

    /**
     * A server left behind by an earlier run of the app (killed while the server ran) still holds
     * the port and gigabytes of memory; find it by its command line and end it. Only processes of
     * this app's own user are visible and killable, so nothing else can be touched.
     */
    fun killLeftovers(executable: String) {
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull()?.takeIf { it != android.os.Process.myPid() }
            val cmdline = pid?.let { runCatching { File(dir, "cmdline").readText() }.getOrNull() }.orEmpty()
            if (pid != null && cmdline.startsWith(executable)) {
                Log.i(TAG, "ending leftover server $pid")
                android.os.Process.killProcess(pid)
            }
        }
    }

    /** The last lines of the server's log, for an error message when it will not start. */
    fun lastLines(log: File): String =
        runCatching {
            log
                .readLines()
                .takeLast(LOG_LINES)
                .joinToString(" ")
                .take(LOG_CHARS)
        }.getOrDefault("")
}
