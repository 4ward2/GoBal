package com.danielealbano.androidremotecontrolmcp.services.character

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64

@DisplayName("FileCharacterRepository")
class FileCharacterRepositoryTest {
    @TempDir
    lateinit var dir: File

    private var now = 1_000L
    private val thumbnailer = AvatarThumbnailer { png -> png.copyOf(4) }

    // Plain file IO: run it inline so the test's own scheduler is the only one in play.
    private fun repo() = FileCharacterRepository(dir, Dispatchers.Unconfined, thumbnailer, clock = { now })

    private fun json(name: String) = """{"name":"$name","description":"d","first_mes":"hi {{user}}"}""".toByteArray()

    private fun png(name: String): ByteArray {
        val chara = Base64.getEncoder().encodeToString(json(name))
        val data = "chara".toByteArray() + 0 + chara.toByteArray()
        val len = data.size
        return byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) +
            byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()) +
            "tEXt".toByteArray() + data + ByteArray(4) +
            byteArrayOf(0, 0, 0, 0) + "IEND".toByteArray() + ByteArray(4)
    }

    @Test
    fun `import stores the card, shrinks the avatar, and survives a reload`() =
        runTest {
            val repo = repo()
            val card = repo.import(png("小夏")).getOrThrow()
            assertEquals("小夏", card.name)
            assertTrue(card.hasAvatar)
            assertEquals(1_000L, card.importedAt)
            assertEquals(4, repo.avatarFile(card.id)!!.length())

            val again = repo()
            again.load()
            assertEquals(listOf(card), again.characters.value)
        }

    @Test
    fun `the same card is not imported twice, even from a different file type`() =
        runTest {
            val repo = repo()
            val first = repo.import(png("小夏")).getOrThrow()
            now = 2_000L
            val second = repo.import(json("小夏")).getOrThrow()
            assertEquals(first, second)
            assertEquals(1, repo.characters.value.size)
            assertEquals(true, repo.isImported(json("小夏")))
            assertEquals(false, repo.isImported(json("别人")))
            assertNull(repo.isImported("nope".toByteArray()))
        }

    @Test
    fun `json cards have no avatar and newest comes first`() =
        runTest {
            val repo = repo()
            val a = repo.import(json("A")).getOrThrow()
            now = 5_000L
            val b = repo.import(json("B")).getOrThrow()
            assertFalse(a.hasAvatar)
            assertNull(repo.avatarFile(a.id))
            assertEquals(listOf(b, a), repo.characters.value)
            val reloaded = repo().also { it.load() }
            assertEquals(listOf(b, a), reloaded.characters.value)
        }

    @Test
    fun `active character persists, and deleting it clears the choice`() =
        runTest {
            val repo = repo()
            val card = repo.import(json("小夏")).getOrThrow()
            repo.setActive(card.id)
            assertEquals(card.id, repo().also { it.load() }.activeId.value)
            repo.setActive("unknown")
            assertNull(repo.activeId.value)
            repo.setActive(card.id)
            repo.delete(card.id)
            assertTrue(repo.characters.value.isEmpty())
            assertNull(repo.activeId.value)
            assertNull(repo().also { it.load() }.activeId.value)
        }

    @Test
    fun `not a card is a clear failure`() =
        runTest {
            val result = repo().import("just text".toByteArray())
            assertTrue(result.isFailure)
            assertNotNull(result.exceptionOrNull()?.message)
        }

    @Test
    fun `status is stored with the card and survives a reload`() =
        runTest {
            val repo = repo()
            val card = repo.import(json("小夏")).getOrThrow()
            repo.updateStatus(card.id, "  生闷气中 ")
            assertEquals(
                "生闷气中",
                repo.characters.value
                    .single()
                    .status,
            )

            val again = repo()
            again.load()
            assertEquals(
                "生闷气中",
                again.characters.value
                    .single()
                    .status,
            )

            again.updateStatus("no-such-id", "x")
            assertEquals(
                "生闷气中",
                again.characters.value
                    .single()
                    .status,
            )
        }

    @Test
    fun `picking a character moves it to the top of the stack and the order survives a reload`() =
        runTest {
            val repo = repo()
            val a = repo.import(json("A")).getOrThrow()
            now = 2_000L
            val b = repo.import(json("B")).getOrThrow()
            now = 3_000L
            val c = repo.import(json("C")).getOrThrow()
            assertEquals(listOf(c.id, b.id, a.id), repo.characters.value.map { it.id })

            now = 4_000L
            repo.setActive(a.id)
            assertEquals(listOf(a.id, c.id, b.id), repo.characters.value.map { it.id })
            assertEquals(4_000L, repo.characters.value.first().selectedAt)

            now = 5_000L
            repo.setActive(b.id)
            repo.setActive(null)
            assertEquals(listOf(b.id, a.id, c.id), repo.characters.value.map { it.id })

            val again = repo().also { it.load() }
            assertEquals(listOf(b.id, a.id, c.id), again.characters.value.map { it.id })
            assertNull(again.activeId.value)
        }
}
