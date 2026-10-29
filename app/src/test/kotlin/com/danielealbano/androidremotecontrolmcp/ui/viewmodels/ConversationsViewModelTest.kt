package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterCard
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBubble
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationCatalog
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationFiles
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ConversationsViewModel")
class ConversationsViewModelTest {
    @TempDir
    lateinit var dir: File

    private val dispatcher = StandardTestDispatcher()
    private val owner = "abc"
    private var clock = 1_000L
    private var ids = 0
    private val files by lazy { ConversationFiles(dir) { clock } }
    private val catalog by lazy { ConversationCatalog(files, { "c${++ids}" }, { clock }) }
    private val store = mockk<ConversationStore>()
    private val characters = mockk<CharacterRepository>(relaxed = true)
    private val settings = mockk<AssistSettingsRepository>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { store.catalog } answers { catalog }
        coEvery { store.owner() } returns owner
        every { characters.characters } returns MutableStateFlow(listOf(CharacterCard(id = owner, name = "小夏")))
        every { settings.assistantName } returns flowOf("小助手")
        files.save(owner, ConversationFiles.MAIN, listOf(ChatBubble("user", "周末去爬山吗", id = "m1")))
        clock = 2_000L
        files.save(ConversationFiles.ASSISTANT, ConversationFiles.MAIN, listOf(ChatBubble("user", "查爬山路线", id = "z1")))
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ConversationsViewModel(store, characters, settings, mockk(relaxed = true), dispatcher)

    @Test
    fun `showing lists the current contact's conversations and marks the open one`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.show()
            advanceUntilIdle()
            assertTrue(vm.ui.value.open)
            val ui = vm.ui.value
            assertEquals(listOf("周末去爬山吗"), ui.conversations.map { it.title })
            assertEquals(ConversationFiles.MAIN, ui.activeId)
        }

    @Test
    fun `starting a new conversation opens it and closes the list`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.show()
            vm.startNew()
            advanceUntilIdle()
            assertFalse(vm.ui.value.open)
            assertEquals("c1", catalog.activeId(owner))
        }

    @Test
    fun `renaming, pinning and deleting refresh the list`() =
        runTest(dispatcher) {
            val vm = viewModel()
            catalog.create(owner)
            vm.change(ConversationChange.Rename(ConversationFiles.MAIN, "周末计划"))
            vm.change(ConversationChange.Pin(ConversationFiles.MAIN, pinned = true))
            advanceUntilIdle()
            val first =
                vm.ui.value.conversations
                    .first()
            assertEquals("周末计划", first.title)
            assertTrue(first.pinned)
            vm.change(ConversationChange.Delete("c1"))
            advanceUntilIdle()
            val left = vm.ui.value.conversations
            assertEquals(listOf(ConversationFiles.MAIN), left.map { it.id })
        }

    @Test
    fun `search covers every contact and names whose conversation each hit is in`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.search("爬山")
            advanceUntilIdle()
            val ui = vm.ui.value
            assertEquals(listOf("z1", "m1"), ui.hits.map { it.messageId })
            assertEquals(mapOf(ConversationFiles.ASSISTANT to "小助手", owner to "小夏"), ui.names)
        }

    @Test
    fun `a result of a search that was typed over is dropped`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.search("爬山")
            vm.search("不存在")
            advanceUntilIdle()
            val ui = vm.ui.value
            assertTrue(ui.hits.isEmpty())
            assertEquals("不存在", ui.query)
        }

    @Test
    fun `opening a hit switches to its contact and conversation and asks for the message`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.search("爬山")
            advanceUntilIdle()
            val inAssistant = vm.ui.value.hits
            vm.openHit(inAssistant.first())
            advanceUntilIdle()
            coVerify { characters.setActive(null) }
            assertEquals("z1", catalog.takeJump())
            vm.search("周末")
            advanceUntilIdle()
            val inCharacter = vm.ui.value.hits
            vm.openHit(inCharacter.first())
            advanceUntilIdle()
            coVerify { characters.setActive(owner) }
            assertEquals("m1", catalog.takeJump())
        }
}
