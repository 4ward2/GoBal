package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.data.model.AssistantProfile
import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterCard
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryLibrary
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryRepository
import com.danielealbano.androidremotecontrolmcp.services.memory.MemorySelection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ConversationStore")
class ConversationStoreTest {
    private val card = CharacterCard(id = "abc", name = "小夏", firstMessage = "喵", model = ModelChoice("p", "m"))
    private val library = mockk<MemoryLibrary>()
    private val characters = mockk<CharacterRepository>()
    private val assistSettings = mockk<AssistSettingsRepository>()
    private val history = mockk<PhoneMcpChatHistory>(relaxed = true)
    private val drafts = mockk<DraftStore>(relaxed = true)
    private val catalog = mockk<ConversationCatalog> { every { activeId(any()) } returns "c7" }
    private val activeId = MutableStateFlow<String?>(null)

    private fun store(): ConversationStore {
        coEvery { characters.load() } returns Unit
        every { characters.activeId } returns activeId
        every { characters.characters } returns MutableStateFlow(listOf(card))
        coEvery { characters.updateStatus(any(), any()) } returns Unit
        every { assistSettings.assistantName } returns flowOf("小助手")
        every { assistSettings.assistantProfile } returns flowOf(AssistantProfile(description = "很耐心"))
        every { assistSettings.assistantModel } returns flowOf(ModelChoice("p2", "m2"))
        every { assistSettings.providers } returns flowOf(emptyList())
        coEvery { library.select("abc", any(), any()) } returns MemorySelection(prompt = "【记忆资料】\n《手册》\n小鱼干在柜子里")
        coEvery { library.select(MemoryRepository.ASSISTANT, any(), any()) } returns MemorySelection()
        return ConversationStore(characters, assistSettings, history, library, drafts, catalog)
    }

    @Test
    fun `the system prompt carries the memory files of whoever is talking`() =
        runTest {
            activeId.value = "abc"
            val prompt = store().systemPrompt()
            assertTrue(prompt.contains("小鱼干在柜子里"))
            assertTrue(prompt.contains("小夏"))
            activeId.value = null
            val assistant = store().systemPrompt()
            assertTrue(assistant.contains("小助手"))
            assertTrue(assistant.contains("很耐心"))
            assertTrue(!assistant.contains("记忆资料"))
        }

    @Test
    fun `each conversation of each contact has its own draft key`() =
        runTest {
            activeId.value = "abc"
            assertEquals("abc/c7", store().draftKey())
            activeId.value = null
            assertEquals("assistant/c7", store().draftKey())
        }

    @Test
    fun `the model choice follows the active contact`() =
        runTest {
            activeId.value = "abc"
            assertEquals(ModelChoice("p", "m"), store().modelChoice())
            assertEquals("abc", store().owner())
            activeId.value = null
            assertEquals(ModelChoice("p2", "m2"), store().modelChoice())
            assertEquals(MemoryRepository.ASSISTANT, store().owner())
        }

    @Test
    fun `a character's status tag is remembered and removed from the reply`() =
        runTest {
            activeId.value = "abc"
            assertEquals("哼。", store().receive("哼。\n<status>生闷气中</status>"))
            coVerify(exactly = 1) { characters.updateStatus("abc", "生闷气中") }
        }

    @Test
    fun `the assistant's replies are only cleaned`() =
        runTest {
            activeId.value = null
            assertEquals("好的。", store().receive("好的。<status>忙碌</status>"))
            coVerify(exactly = 0) { characters.updateStatus(any(), any()) }
        }

    @Test
    fun `a reply without a tag leaves the status alone`() =
        runTest {
            activeId.value = "abc"
            assertEquals("今天不错。", store().receive("今天不错。"))
            coVerify(exactly = 0) { characters.updateStatus(any(), any()) }
        }
}
