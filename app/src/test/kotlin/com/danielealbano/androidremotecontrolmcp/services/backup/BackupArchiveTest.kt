package com.danielealbano.androidremotecontrolmcp.services.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@DisplayName("BackupArchive")
class BackupArchiveTest {
    @TempDir
    lateinit var from: File

    @TempDir
    lateinit var to: File

    private fun put(
        root: File,
        path: String,
        text: String,
    ) = File(root, path).apply { parentFile.mkdirs() }.writeText(text)

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun namesIn(zip: ByteArray): List<String> =
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            generateSequence { input.nextEntry }.map { it.name }.toList()
        }

    @Test
    fun `the user's content goes in, secrets and scratch files stay out`() {
        put(from, "conversations/assistant/main.json", "[]")
        put(from, "characters/abc.json", "{}")
        put(from, "memory/abc/index.json", "[]")
        put(from, "skills/skills.json", "[]")
        put(from, "attachments/a.jpg", "jpg")
        put(from, "attachments/exports/对话.md", "#")
        put(from, "attachments/camera_shot.jpg", "x")
        put(from, "conversations/assistant/main.json.tmp", "half")
        put(from, "vault/secrets.bin", "secret")
        put(from, "datastore/settings.preferences_pb", "token")
        put(from, "agent/app_grants.json", "{}")
        put(from, "groups/index.json", "[]")
        put(from, "im/weixin.bin", "login")
        put(from, "remote/computers.bin", "pairing")
        val out = ByteArrayOutputStream()
        assertEquals(6, BackupArchive(from).export(out))
        val names = namesIn(out.toByteArray()).sorted()
        val expected =
            listOf(
                "attachments/a.jpg",
                "characters/abc.json",
                "conversations/assistant/main.json",
                "groups/index.json",
                "memory/abc/index.json",
                "skills/skills.json",
            )
        assertEquals(expected, names)
    }

    @Test
    fun `a backup put back on another phone holds the same files, over what was there`() {
        put(from, "conversations/assistant/main.json", "[1]")
        put(from, "memory/abc/x.txt", "小鱼干在柜子里")
        put(to, "conversations/assistant/main.json", "[old]")
        put(to, "conversations/assistant/other.json", "[kept]")
        val out = ByteArrayOutputStream()
        BackupArchive(from).export(out)
        assertEquals(2, BackupArchive(to).import(ByteArrayInputStream(out.toByteArray())))
        assertEquals("[1]", File(to, "conversations/assistant/main.json").readText())
        assertEquals("小鱼干在柜子里", File(to, "memory/abc/x.txt").readText())
        assertEquals("[kept]", File(to, "conversations/assistant/other.json").readText())
        assertTrue(to.walkTopDown().none { it.name.endsWith(".restoring") })
    }

    @Test
    fun `entries that point outside the backed-up folders are never written`() {
        val archive = BackupArchive(to)
        assertNull(archive.targetOf("../evil.txt"))
        assertNull(archive.targetOf("conversations/../../evil.txt"))
        assertNull(archive.targetOf("conversations/../vault/secrets.bin"))
        assertNull(archive.targetOf("/etc/passwd"))
        assertNull(archive.targetOf("vault/secrets.bin"))
        assertNull(archive.targetOf("datastore/settings.preferences_pb"))
        assertNull(archive.targetOf("conversations"))
        assertNull(archive.targetOf("conversations\\..\\..\\evil.txt"))
        val skills = File(to, "skills/skills.json").canonicalPath
        assertEquals(skills, archive.targetOf("skills/skills.json")?.canonicalPath)
        val zip =
            zipOf(
                "../evil.txt" to "x",
                "vault/secrets.bin" to "x",
                "conversations/../../evil2.txt" to "x",
                "skills/skills.json" to "[]",
            )
        assertEquals(1, archive.import(ByteArrayInputStream(zip)))
        assertFalse(File(to.parentFile, "evil.txt").exists())
        assertFalse(File(to.parentFile, "evil2.txt").exists())
        assertFalse(File(to, "vault").exists())
    }

    @Test
    fun `a file that is no backup is refused and changes nothing`() {
        val archive = BackupArchive(to)
        assertThrows(IOException::class.java) { archive.import(ByteArrayInputStream("not a zip".toByteArray())) }
        assertThrows(IOException::class.java) { archive.import(ByteArrayInputStream(zipOf("readme.txt" to "hi"))) }
        assertTrue(to.listFiles().orEmpty().isEmpty())
    }
}
