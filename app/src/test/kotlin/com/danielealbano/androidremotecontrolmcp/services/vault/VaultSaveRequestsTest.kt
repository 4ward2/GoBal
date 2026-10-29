package com.danielealbano.androidremotecontrolmcp.services.vault

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("VaultSaveRequests")
class VaultSaveRequestsTest {
    private val repository = mockk<VaultRepository>()
    private val requests = VaultSaveRequests(repository)
    private val existing = VaultSlot("phone", "手机号", VaultCategory.CONTACT, false, listOf("com.taobao.taobao"), true)
    private val draft =
        VoiceDraft("phone", "手机号", VaultCategory.CONTACT, false, VoiceValueKind.PHONE, "13912345678", existing, null)

    @Test
    fun `confirm stores the draft once, keeps the app restriction and clears the prompt`() =
        runTest {
            val saved = slot<VaultEntry>()
            coEvery { repository.upsert(capture(saved)) } returns Result.success(Unit)
            requests.offer(draft)
            assertEquals("手机号", requests.confirm())
            assertNull(requests.confirm())
            coVerify(exactly = 1) { repository.upsert(any()) }
            assertEquals("13912345678", saved.captured.value)
            assertEquals(listOf("com.taobao.taobao"), saved.captured.allowedPackages)
            assertNull(requests.pending.value)
        }

    @Test
    fun `a failed save keeps the prompt open with the reason`() =
        runTest {
            coEvery { repository.upsert(any()) } returns Result.failure(IllegalArgumentException("名称最多 24 个字"))
            requests.offer(draft)
            assertNull(requests.confirm())
            assertEquals("名称最多 24 个字", requests.pending.value?.error)
            assertEquals(false, requests.pending.value?.saving)
        }

    @Test
    fun `dismiss forgets the draft without saving`() =
        runTest {
            requests.offer(draft)
            requests.dismiss()
            assertNull(requests.pending.value)
            coVerify(exactly = 0) { repository.upsert(any()) }
        }
}
