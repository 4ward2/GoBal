package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.call.CallSession
import com.danielealbano.androidremotecontrolmcp.services.call.CallSessionFactory
import com.danielealbano.androidremotecontrolmcp.services.call.CallUiState
import com.danielealbano.androidremotecontrolmcp.services.call.FrameSource
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBackend
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("CallViewModel")
class CallViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val factory = mockk<CallSessionFactory>()
    private val store = mockk<ConversationStore>()
    private val backend = mockk<ChatBackend> { every { store } returns this@CallViewModelTest.store }
    private val settings = mockk<AssistSettingsRepository> { every { assistantName } returns flowOf("小助手") }
    private val characters = mockk<CharacterRepository>(relaxed = true)
    private lateinit var viewModel: CallViewModel

    /** A stand-in session whose state the test drives by hand. */
    private class FakeSession {
        val state = MutableStateFlow(CallUiState())
        val session =
            mockk<CallSession>(relaxed = true) {
                every { ui } returns state
                every { hangUp() } answers { state.update { it.copy(ended = true, connected = false) } }
            }
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { store.activeCharacter() } returns null
        viewModel = CallViewModel(factory, backend, settings, characters, dispatcher)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a second call starts fresh instead of inheriting the first call's ended state`() {
        val first = FakeSession()
        val second = FakeSession()
        every { factory.create() } returnsMany listOf(first.session, second.session)

        viewModel.start(video = false)
        assertTrue(viewModel.active.value)
        first.state.update { it.copy(connected = true) }
        viewModel.hangUp()
        assertTrue(viewModel.ui.value.ended)
        assertFalse(viewModel.active.value)

        viewModel.start(video = false)
        assertFalse(viewModel.ui.value.ended)
        second.state.update { it.copy(connected = true) }
        assertTrue(viewModel.ui.value.connected)
        // The old session can still change; the screen no longer listens to it.
        first.state.update { it.copy(ended = true, error = "stale") }
        assertFalse(viewModel.ui.value.ended)
        verify(exactly = 1) { first.session.hangUp() }
    }

    @Test
    fun `starting again while a call runs keeps the running call`() {
        val first = FakeSession()
        every { factory.create() } returns first.session
        viewModel.start(video = false)
        viewModel.start(video = true)
        verify(exactly = 1) { factory.create() }
        verify(exactly = 0) { first.session.hangUp() }
    }

    @Test
    fun `the camera reaches the running session once bound`() {
        val fake = FakeSession()
        every { factory.create() } returns fake.session
        viewModel.start(video = true)
        val frames = FrameSource { null }
        viewModel.attachCamera(frames)
        verify { fake.session.attachFrames(frames) }
    }

    @Test
    fun `the callee is the assistant when no character is active`() {
        every { factory.create() } returns FakeSession().session
        viewModel.start(video = false)
        assertEquals("小助手", viewModel.callee.value.name)
    }

    @Test
    fun `mute and speakerphone toggle from the mirrored state`() {
        val fake = FakeSession()
        every { factory.create() } returns fake.session
        every { fake.session.setMuted(any()) } answers { fake.state.update { it.copy(muted = firstArg()) } }
        viewModel.start(video = false)
        viewModel.toggleMute()
        assertTrue(viewModel.ui.value.muted)
        viewModel.toggleMute()
        assertFalse(viewModel.ui.value.muted)
    }
}
