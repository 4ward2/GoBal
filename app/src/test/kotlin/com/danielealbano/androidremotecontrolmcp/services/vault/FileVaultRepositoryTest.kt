package com.danielealbano.androidremotecontrolmcp.services.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import javax.crypto.AEADBadTagException

@DisplayName("FileVaultRepository")
class FileVaultRepositoryTest {
    /** Reversible XOR with a checksum byte: proves files are not plaintext and detects tampering. */
    private class FakeCipher : VaultCipher {
        override fun encrypt(plain: ByteArray): ByteArray = byteArrayOf(checksum(plain)) + xor(plain)

        override fun decrypt(blob: ByteArray): ByteArray {
            val plain = xor(blob.copyOfRange(1, blob.size))
            if (checksum(plain) != blob[0]) throw AEADBadTagException("tampered")
            return plain
        }

        private fun xor(bytes: ByteArray) = ByteArray(bytes.size) { (bytes[it].toInt() xor KEY).toByte() }

        private fun checksum(bytes: ByteArray): Byte = bytes.fold(0) { acc, b -> acc + b }.toByte()

        companion object {
            const val KEY = 0x5A
        }
    }

    @TempDir
    lateinit var dir: File

    private lateinit var repo: FileVaultRepository
    private val now = 1_000L

    private val phone = VaultEntry("phone", "手机号", VaultCategory.CONTACT, "13800138000", sensitive = false)
    private val password =
        VaultEntry(
            "password",
            "登录密码",
            VaultCategory.CREDENTIAL,
            "s3cret-Pa55",
            sensitive = true,
            allowedPackages = listOf("com.taobao.taobao"),
        )

    @BeforeEach
    fun setUp() {
        repo = newRepo()
    }

    private fun newRepo() = FileVaultRepository(dir, FakeCipher(), Dispatchers.Unconfined) { now }

    private fun decrypted(name: String): String {
        val bytes = File(dir, name).readBytes()
        return String(FakeCipher().decrypt(bytes))
    }

    private fun rawFile(name: String): String = String(File(dir, name).readBytes(), Charsets.ISO_8859_1)

    @Test
    fun `non credential round trips and slots never carry the value`() =
        runTest {
            assertTrue(repo.upsert(phone).isSuccess)
            assertEquals("13800138000", repo.reveal("phone")?.value)
            assertEquals(1_000L, repo.reveal("phone")?.updatedAt)
            val slot = repo.slots.value.single()
            assertEquals("phone", slot.id)
            assertFalse(slot.toString().contains("13800138000"))
        }

    @Test
    fun `files on disk do not contain plaintext values`() =
        runTest {
            repo.upsert(phone)
            repo.upsert(password)
            assertFalse(rawFile("profile.bin").contains("13800138000"))
            assertFalse(rawFile("secrets.bin").contains("s3cret-Pa55"))
        }

    @Test
    fun `credential value lives only in the secrets file`() =
        runTest {
            repo.upsert(password)
            val profile = decrypted("profile.bin")
            val secrets = decrypted("secrets.bin")
            assertFalse(profile.contains("s3cret-Pa55"))
            assertTrue(secrets.contains("s3cret-Pa55"))
            assertEquals("s3cret-Pa55", repo.reveal("password")?.value)
            val slots = repo.slots.value
            assertTrue(slots.single().hasValue)
        }

    @Test
    fun `data survives a new repository instance`() =
        runTest {
            repo.upsert(phone)
            repo.upsert(password)
            val reopened = newRepo()
            reopened.load()
            assertEquals(listOf("phone", "password"), reopened.slots.value.map { it.id })
            assertEquals("s3cret-Pa55", reopened.reveal("password")?.value)
        }

    @Test
    fun `redactor covers profile values but not credentials`() =
        runTest {
            repo.upsert(phone)
            repo.upsert(password)
            val r = repo.redactor.value
            assertEquals("‹vault:phone›", r.redact("13800138000"))
            assertEquals("s3cret-Pa55", r.redact("s3cret-Pa55"))
        }

    @Test
    fun `invalid entry is rejected without writing`() =
        runTest {
            val bad = phone.copy(id = "Bad Id")
            assertTrue(repo.upsert(bad).isFailure)
            assertTrue(repo.slots.value.isEmpty())
            assertFalse(File(dir, "profile.bin").exists())
        }

    @Test
    fun `delete removes slot, secret and approvals`() =
        runTest {
            repo.upsert(password)
            repo.rememberApproval("password", "com.taobao.taobao")
            repo.delete("password")
            assertNull(repo.reveal("password"))
            assertFalse(repo.isApproved("password", "com.taobao.taobao"))
            assertFalse(decrypted("secrets.bin").contains("s3cret"))
        }

    @Test
    fun `editing a slot clears its remembered approvals only`() =
        runTest {
            repo.upsert(phone)
            repo.upsert(phone.copy(id = "phone2"))
            repo.rememberApproval("phone", "com.taobao.taobao")
            repo.rememberApproval("phone2", "com.taobao.taobao")
            repo.upsert(phone.copy(value = "13900139000"))
            assertFalse(repo.isApproved("phone", "com.taobao.taobao"))
            assertTrue(repo.isApproved("phone2", "com.taobao.taobao"))
        }

    @Test
    fun `changing a credential to a normal slot drops the stored secret`() =
        runTest {
            repo.upsert(password)
            repo.upsert(password.copy(category = VaultCategory.OTHER, value = "plain", sensitive = false))
            assertEquals("plain", repo.reveal("password")?.value)
            assertFalse(decrypted("secrets.bin").contains("s3cret"))
        }

    @Test
    fun `fill log is newest first and capped`() =
        runTest {
            repeat(105) { i -> repo.recordFill(VaultFillRecord("phone", "p.k$i", i.toLong(), "已填写")) }
            val log = repo.fillLog.value
            assertEquals(100, log.size)
            assertEquals("p.k104", log.first().packageName)
        }

    @Test
    fun `tampered file fails loudly instead of being overwritten`() =
        runTest {
            repo.upsert(phone)
            val f = File(dir, "profile.bin")
            val bytes = f.readBytes()
            bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
            f.writeBytes(bytes)
            val reopened = newRepo()
            assertTrue(runCatching { reopened.load() }.exceptionOrNull() is IOException)
            assertTrue(f.readBytes().contentEquals(bytes))
        }

    @Test
    fun `entry toString never prints the value`() {
        assertFalse(password.toString().contains("s3cret"))
    }
}
