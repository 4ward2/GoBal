package com.danielealbano.androidremotecontrolmcp.services.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("VaultPolicy")
class VaultPolicyTest {
    private val self = "com.example.assistant"

    private fun slot(
        category: VaultCategory = VaultCategory.CONTACT,
        sensitive: Boolean = false,
        allowed: List<String> = emptyList(),
    ) = VaultSlot("phone", "手机号", category, sensitive, allowed, hasValue = true)

    private fun field(
        pkg: String? = "com.taobao.taobao",
        editable: Boolean = true,
        enabled: Boolean = true,
        password: Boolean = false,
    ) = FillTarget(pkg, editable, enabled, password)

    private fun decide(
        slot: VaultSlot,
        target: FillTarget,
        approved: Boolean = false,
    ) = VaultPolicy.decide(slot, target, self, approved)

    @Test
    fun `non editable node is denied`() {
        assertTrue(decide(slot(), field(editable = false)) is FillDecision.Deny)
    }

    @Test
    fun `disabled field is denied`() {
        assertTrue(decide(slot(), field(enabled = false)) is FillDecision.Deny)
    }

    @Test
    fun `unknown package is denied`() {
        assertTrue(decide(slot(), field(pkg = null)) is FillDecision.Deny)
        assertTrue(decide(slot(), field(pkg = " ")) is FillDecision.Deny)
    }

    @Test
    fun `assistant own ui is always denied`() {
        val d = decide(slot(), field(pkg = self), approved = true)
        assertTrue(d is FillDecision.Deny)
    }

    @Test
    fun `package outside allow list is denied`() {
        val d = decide(slot(allowed = listOf("com.sankuai.meituan")), field(pkg = "com.taobao.taobao"), approved = true)
        assertTrue(d is FillDecision.Deny)
    }

    @Test
    fun `package inside allow list passes`() {
        val d = decide(slot(allowed = listOf("com.taobao.taobao")), field(), approved = true)
        assertEquals(FillDecision.Allow, d)
    }

    @Test
    fun `credential into a plain text field is denied`() {
        val d = decide(slot(VaultCategory.CREDENTIAL, sensitive = true), field(password = false), approved = true)
        assertTrue(d is FillDecision.Deny)
    }

    @Test
    fun `credential into a password field needs approval every time`() {
        val d = decide(slot(VaultCategory.CREDENTIAL, sensitive = true), field(password = true), approved = true)
        assertEquals(FillDecision.NeedsApproval(rememberable = false), d)
    }

    @Test
    fun `sensitive non credential needs non rememberable approval even if approved before`() {
        val d = decide(slot(VaultCategory.IDENTITY, sensitive = true), field(), approved = true)
        assertEquals(FillDecision.NeedsApproval(rememberable = false), d)
    }

    @Test
    fun `first fill of a normal slot in an app needs rememberable approval`() {
        assertEquals(FillDecision.NeedsApproval(rememberable = true), decide(slot(), field(), approved = false))
    }

    @Test
    fun `previously approved normal slot is allowed`() {
        assertEquals(FillDecision.Allow, decide(slot(), field(), approved = true))
    }
}
