package com.danielealbano.androidremotecontrolmcp.services.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("DraftFile")
class DraftFileTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a draft survives a new instance and each conversation has its own`() {
        val file = File(dir, "drafts.json")
        DraftFile(file).save("assistant/main", "写到一半")
        DraftFile(file).save("abc/main", "另一段")
        val reopened = DraftFile(file)
        assertEquals("写到一半", reopened.load("assistant/main"))
        assertEquals("另一段", reopened.load("abc/main"))
        assertEquals("", reopened.load("nobody/main"))
    }

    @Test
    fun `saving blank text forgets the draft`() {
        val drafts = DraftFile(File(dir, "drafts.json"))
        drafts.save("k", "有内容")
        drafts.save("k", "  ")
        assertEquals("", drafts.load("k"))
    }

    @Test
    fun `an unreadable file reads as no drafts and the next save repairs it`() {
        val file = File(dir, "drafts.json").apply { writeText("{broken") }
        val drafts = DraftFile(file)
        assertEquals("", drafts.load("k"))
        drafts.save("k", "新的")
        assertEquals("新的", DraftFile(file).load("k"))
    }

    @Test
    fun `an atomic write replaces the file and leaves no temporary file behind`() {
        val file = File(dir, "sub/data.json")
        AtomicFiles.write(file, "one")
        AtomicFiles.write(file, "two")
        assertEquals("two", file.readText())
        assertFalse(File(dir, "sub/data.json.tmp").exists())
    }
}
