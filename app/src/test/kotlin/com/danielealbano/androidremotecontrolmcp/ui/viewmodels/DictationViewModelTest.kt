package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.services.assistant.AssistantLocalFirst
import com.danielealbano.androidremotecontrolmcp.services.assistant.LocalOutcome
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpLoopbackClient
import com.danielealbano.androidremotecontrolmcp.services.chat.VoskSpeechClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("DictationViewModel")
class DictationViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val client = mockk<PhoneMcpLoopbackClient>(relaxed = true)
    private val vosk = mockk<VoskSpeechClient> { every { prepare(any()) } returns Unit }
    private val localFirst = mockk<AssistantLocalFirst>()
    private lateinit var viewModel: DictationViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = DictationViewModel(client, vosk, localFirst, dispatcher)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a spoken vault sentence is confirmed on the phone and never offered as draft text`() {
        coEvery { localFirst.handle(any()) } returns LocalOutcome.Handled(message = "已记下")
        every { vosk.listenHeld(any(), any(), any(), any()) } returns "我的学号是二零二一零一零五"
        viewModel.begin()
        assertNull(viewModel.state.value.spoken)
        assertEquals("已记下", viewModel.state.value.error)
    }

    @Test
    fun `ordinary speech lands in spoken and is cleared once consumed`() {
        coEvery { localFirst.handle(any()) } returns LocalOutcome.Forward(phoneControl = false)
        every { vosk.listenHeld(any(), any(), any(), any()) } returns "明天天气"
        viewModel.begin()
        assertEquals("明天天气", viewModel.state.value.spoken)
        viewModel.spokenConsumed()
        assertNull(viewModel.state.value.spoken)
    }
}
