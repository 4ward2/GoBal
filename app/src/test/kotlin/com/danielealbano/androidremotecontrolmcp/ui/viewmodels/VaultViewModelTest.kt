package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.services.vault.VaultCategory
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultRepository
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultSlot
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("VaultViewModel manual entry")
class VaultViewModelTest {
    private val slots = MutableStateFlow<List<VaultSlot>>(emptyList())
    private lateinit var viewModel: VaultViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val repository = mockk<VaultRepository>(relaxed = true)
        every { repository.slots } returns slots
        every { repository.fillLog } returns MutableStateFlow(emptyList())
        viewModel = VaultViewModel(repository)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `manual entry starts as a password with a ready slot name`() {
        viewModel.startNew()
        val form = viewModel.form.value!!
        assertTrue(form.isNew)
        assertEquals(VaultCategory.CREDENTIAL, form.category)
        assertTrue(form.sensitive)
        assertEquals("password", form.id)
    }

    @Test
    fun `the slot name never clashes with an existing one`() {
        slots.value = listOf(VaultSlot("password", "登录密码", VaultCategory.CREDENTIAL, true, emptyList(), true))
        viewModel.startNew()
        assertEquals("password_2", viewModel.form.value!!.id)
    }
}
