package com.danielealbano.androidremotecontrolmcp.services.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AtomicFilesTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a save replaces the file and leaves no scratch file`() {
        val file = File(dir, "nested/a.json").apply { parentFile.mkdirs() }
        file.writeText("old")
        assertTrue(AtomicFiles.save(file, "new"))
        assertEquals("new", file.readText())
        assertEquals(listOf("a.json"), file.parentFile.list()!!.toList())
    }

    @Test
    fun `a save that cannot be written reports it and keeps what was there`() {
        val blocker = File(dir, "plain").apply { writeText("kept") }
        // The parent "folder" is a file, so nothing can be created under it.
        assertFalse(AtomicFiles.save(File(blocker, "a.json"), "new"))
        assertEquals("kept", blocker.readText())
    }
}
