package com.danielealbano.androidremotecontrolmcp.services.backup

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The user's own content as one zip file, to keep or to move to another phone: conversations,
 * characters, memory files, skills and the pictures and documents sent in chats.
 *
 * Left out on purpose: the personal vault (its key never leaves this phone, a copy could not be
 * opened anyway), provider keys and the server token (settings), and anything that is downloaded
 * again when needed (speech and OCR models, local models).
 */
class BackupArchive(
    private val filesDir: File,
) {
    /** Writes the backup to [out] and returns how many files went in. */
    fun export(out: OutputStream): Int {
        val files =
            ROOTS
                .map { File(filesDir, it) }
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && wanted(it) } }
        ZipOutputStream(out).use { zip -> files.forEach { add(zip, it) } }
        return files.size
    }

    private fun add(
        zip: ZipOutputStream,
        file: File,
    ) {
        zip.putNextEntry(ZipEntry(file.relativeTo(filesDir).invariantSeparatorsPath))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /**
     * Puts the files of a backup back, over what is there. An entry that is not one of the
     * backed-up folders, or that points outside them, is skipped: a zip from anywhere must not be
     * able to write where it likes. Returns how many files were restored.
     */
    fun import(input: InputStream): Int {
        var count = 0
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val target = if (entry.isDirectory) null else targetOf(entry.name)
                if (target != null) {
                    restore(zip, target)
                    count++
                }
            }
        }
        if (count == 0) throw IOException(NOT_A_BACKUP)
        return count
    }

    /** Where the entry [name] belongs, or null when it has no business being restored. */
    internal fun targetOf(name: String): File? {
        val parts = name.replace('\\', '/').split('/')
        val plain = parts.none { it.isEmpty() || it == "." || it == ".." }
        val target = File(filesDir, parts.joinToString(File.separator))
        val inside = target.canonicalPath.startsWith(filesDir.canonicalPath + File.separator)
        return target.takeIf { plain && parts.size > 1 && parts.first() in ROOTS && inside && wanted(it) }
    }

    /** Written beside the target first: a restore cut short leaves the old file, not half of the new one. */
    private fun restore(
        zip: ZipInputStream,
        target: File,
    ) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + PART)
        part.outputStream().use { out ->
            val copied = zip.copyTo(out)
            if (copied > MAX_FILE_BYTES) throw IOException(TOO_BIG)
        }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Exports made for sharing, the camera's scratch file and half-written files are not content. */
    private fun wanted(file: File): Boolean {
        val path = file.invariantSeparatorsPath
        return SKIPPED_DIRS.none { path.contains(it) } && SKIPPED_ENDINGS.none { file.name.endsWith(it) }
    }

    companion object {
        val ROOTS = listOf("conversations", "characters", "memory", "skills", "attachments", "groups")
        const val NOT_A_BACKUP = "这个文件里没有可以恢复的内容"
        const val TOO_BIG = "备份里有一个文件太大，没有恢复"
        private const val PART = ".restoring"
        private const val MAX_FILE_BYTES = 512L * 1024 * 1024
        private val SKIPPED_DIRS = listOf("/attachments/exports/")
        private val SKIPPED_ENDINGS = listOf(PART, ".tmp", "camera_shot.jpg")
    }
}
