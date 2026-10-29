package com.danielealbano.androidremotecontrolmcp.services.chat

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Whole-file writes that never leave a half-written file behind. */
object AtomicFiles {
    /**
     * Writes [text] next to [file], forces it to disk, then moves it over [file]: a crash or a
     * full disk part-way leaves the previous contents untouched.
     */
    @Throws(IOException::class)
    fun write(
        file: File,
        text: String,
    ) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * [write] for callers that carry on without the file (a full disk must not crash a chat): the
     * failure is logged, with the file's name and never its contents, and reported as `false`.
     */
    fun save(
        file: File,
        text: String,
    ): Boolean =
        runCatching { write(file, text) }
            .onFailure { Log.w(TAG, "could not save ${file.name}: ${it.javaClass.simpleName}") }
            .isSuccess

    private const val TAG = "MCP:Files"
}
