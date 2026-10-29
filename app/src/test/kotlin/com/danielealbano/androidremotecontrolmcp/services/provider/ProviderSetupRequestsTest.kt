package com.danielealbano.androidremotecontrolmcp.services.provider

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterCard
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationStore
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelGateway
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelInfo
import com.danielealbano.androidremotecontrolmcp.services.chat.PresenceMonitor
import com.danielealbano.androidremotecontrolmcp.services.provider.web.WebPage
import com.danielealbano.androidremotecontrolmcp.services.provider.web.WebPages
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ProviderSetupRequests")
class ProviderSetupRequestsTest {
    private val key = "sk-" + "a1".repeat(16)
    private val deepseek = ProviderPresets.byId("deepseek")!!
    private val saved =
        ModelProvider(
            id = "p1",
            name = "DeepSeek",
            kind = ProviderKind.OPENAI,
            baseUrl = deepseek.baseUrl,
            apiKey = "old-key",
            models = listOf("deepseek-chat"),
            contextWindow = 64000,
        )
    private val settings = mockk<AssistSettingsRepository>(relaxed = true)
    private val gateway = mockk<ModelGateway>()
    private val store = mockk<ConversationStore>()
    private val characters = mockk<CharacterRepository>(relaxed = true)
    private val presence = mockk<PresenceMonitor>(relaxed = true)
    private val pages = mockk<WebPages>()
    private val requests = ProviderSetupRequests(settings, gateway, store, characters, presence, pages)

    private fun given(
        providers: List<ModelProvider> = emptyList(),
        card: CharacterCard? = null,
        choice: ModelChoice = ModelChoice.ON_DEVICE,
    ) {
        every { settings.providers } returns flowOf(providers)
        every { settings.assistantName } returns flowOf("小助手")
        coEvery { store.activeCharacter() } returns card
        coEvery { store.modelChoice() } returns choice
    }

    private fun draft(apiKey: String = key) = ProviderSetupLogic.withPreset(ProviderDraft(apiKey = apiKey), deepseek)

    @Test
    fun `a new service is checked, saved with its models, and given to the contact on the phone model`() =
        runTest {
            given()
            coEvery { gateway.listModels(any()) } returns
                Result.success(listOf(ModelInfo("deepseek-chat", 131_072), ModelInfo("deepseek-reasoner")))
            requests.offer(draft())
            assertTrue(requests.pending.value!!.useForActive)
            assertEquals("小助手", requests.pending.value!!.activeName)
            requests.check()
            assertEquals("deepseek-chat", requests.pending.value!!.model)
            val stored = slot<ModelProvider>()
            coEvery { settings.saveProvider(capture(stored)) } returns Unit

            assertEquals("DeepSeek", requests.confirm())

            assertEquals(key, stored.captured.apiKey)
            assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), stored.captured.models)
            assertEquals(mapOf("deepseek-chat" to 131_072), stored.captured.modelContexts)
            coVerify { settings.updateAssistantModel(ModelChoice(stored.captured.id, "deepseek-chat")) }
            verify { presence.markAlive(ModelChoice(stored.captured.id, "deepseek-chat")) }
            assertNull(requests.pending.value)
        }

    @Test
    fun `a new key for a saved service updates it in place`() =
        runTest {
            val card = CharacterCard(id = "c1", name = "小夏", model = ModelChoice("p1", "deepseek-chat"))
            given(providers = listOf(saved), card = card, choice = card.model)
            requests.offer(draft())
            val setup = requests.pending.value!!
            assertEquals(saved, setup.existing)
            assertFalse(setup.useForActive)
            assertTrue(setup.activeAlreadyUses)
            val stored = slot<ModelProvider>()
            coEvery { settings.saveProvider(capture(stored)) } returns Unit

            requests.confirm()

            assertEquals("p1", stored.captured.id)
            assertEquals(key, stored.captured.apiKey)
            assertEquals(listOf("deepseek-chat"), stored.captured.models)
            assertEquals(64000, stored.captured.contextWindow)
            coVerify(exactly = 0) { characters.update(any()) }
        }

    @Test
    fun `switching the contact on a character updates the card, not the assistant`() =
        runTest {
            val card = CharacterCard(id = "c1", name = "小夏")
            given(card = card)
            requests.offer(draft())
            coEvery { settings.saveProvider(any()) } returns Unit
            requests.confirm()
            coVerify { characters.update(match { it.id == "c1" && it.model.providerId.isNotBlank() }) }
            coVerify(exactly = 0) { settings.updateAssistantModel(any()) }
        }

    @Test
    fun `a documentation link is read without the key and fills in the address, name and model`() =
        runTest {
            given()
            val docsPage =
                """
                <title>Start | Acme AI Docs</title>
                https://api.acme.ai/v1/chat/completions {"model": "acme-large"}
                https://api.acme.ai/v2
                https://api.openai.com/v1/chat/completions
                """.trimIndent()
            every { pages.get("https://docs.acme.ai/start") } returns
                WebPage("https://docs.acme.ai/start", 200, "text/html", docsPage)
            requests.offer(ProviderDraft(apiKey = key, sourceUrl = "https://docs.acme.ai/start"))
            assertEquals(DocsImport.Reading("docs.acme.ai"), requests.pending.value!!.docs)

            requests.importDocs()

            val setup = requests.pending.value!!
            assertEquals("https://api.acme.ai/v1", setup.draft.baseUrl)
            assertEquals("Acme AI", setup.draft.name)
            assertEquals("acme-large", setup.draft.model)
            assertEquals(listOf("https://api.acme.ai/v2"), setup.alternatives.map { it.baseUrl })
            assertEquals(DocsImport.Found("docs.acme.ai"), setup.docs)
        }

    @Test
    fun `when the first address fails the next one from the docs is tried, and a public list is flagged`() =
        runTest {
            given()
            requests.offer(ProviderDraft(apiKey = key, sourceUrl = "https://docs.acme.ai/start"))
            every { pages.get(any()) } returns
                WebPage(
                    url = "https://docs.acme.ai/start",
                    code = 200,
                    contentType = "text/html",
                    text = "https://api.acme.ai/v1/chat/completions https://api.acme.ai/v2",
                )
            requests.importDocs()
            coEvery { gateway.listModels(match { it.baseUrl == "https://api.acme.ai/v1" }) } returns
                Result.failure(IllegalStateException("HTTP 404: no"))
            coEvery { gateway.listModels(match { it.baseUrl == "https://api.acme.ai/v2" }) } returns
                Result.success(listOf(ModelInfo("acme-small")))

            requests.check()

            val setup = requests.pending.value!!
            assertEquals("https://api.acme.ai/v2", setup.draft.baseUrl)
            assertEquals(KeyCheck.Ok(listOf(ModelInfo("acme-small"))), setup.check)
            assertTrue(setup.keyUnproven)
            coVerify(exactly = 0) { gateway.listModels(match { it.baseUrl.contains("openai") }) }
        }

    @Test
    fun `a page that cannot be read or holds no address says so and leaves the fields to the user`() =
        runTest {
            given()
            every { pages.get(any()) } throws java.io.IOException("offline")
            requests.offer(ProviderDraft(sourceUrl = "https://docs.acme.ai/start"))
            requests.importDocs()
            assertTrue(requests.pending.value!!.docs is DocsImport.Failed)
            every { pages.get(any()) } returns WebPage("https://acme.ai", 200, "text/html", "<title>Acme</title>")
            requests.offer(ProviderDraft(sourceUrl = "https://acme.ai"))
            requests.importDocs()
            assertEquals(DocsImport.Failed("这个网页里没找到接口地址，可以在上面手动填写"), requests.pending.value!!.docs)
        }

    @Test
    fun `a check never writes back an older draft over newer typing`() =
        runTest {
            given()
            requests.offer(draft())
            coEvery { gateway.listModels(any()) } coAnswers {
                // The user keeps typing while the service is being asked.
                requests.edit { it.copy(apiKey = key + "x") }
                Result.success(listOf(ModelInfo("deepseek-chat")))
            }
            requests.check()
            assertEquals(
                key + "x",
                requests.pending.value!!
                    .draft.apiKey,
            )
            assertEquals(KeyCheck.Idle, requests.pending.value!!.check)
        }

    @Test
    fun `a failed check is shown and a key edit makes it stale`() =
        runTest {
            given()
            coEvery { gateway.listModels(any()) } returns Result.failure(IllegalStateException("HTTP 401"))
            requests.offer(draft())
            requests.check()
            assertEquals(KeyCheck.Failed("密钥不对或已经失效（HTTP 401）"), requests.pending.value!!.check)
            requests.edit { it.copy(apiKey = "sk-" + "b2".repeat(16)) }
            assertEquals(KeyCheck.Idle, requests.pending.value!!.check)
        }

    @Test
    fun `nothing is saved without a key, and a second confirm saves nothing`() =
        runTest {
            given()
            requests.offer(draft(apiKey = ""))
            assertNull(requests.confirm())
            coVerify(exactly = 0) { settings.saveProvider(any()) }
            requests.edit { it.copy(apiKey = key) }
            coEvery { settings.saveProvider(any()) } returns Unit
            assertEquals("DeepSeek", requests.confirm())
            assertNull(requests.confirm())
            coVerify(exactly = 1) { settings.saveProvider(any()) }
        }

    @Test
    fun `a save failure keeps the card open with the reason`() =
        runTest {
            given()
            requests.offer(draft())
            coEvery { settings.saveProvider(any()) } throws IllegalStateException("disk full")
            assertNull(requests.confirm())
            assertEquals("disk full", requests.pending.value!!.error)
            assertFalse(requests.pending.value!!.saving)
        }
}
