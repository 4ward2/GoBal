package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentHistoryFile
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRunner
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRuns
import com.danielealbano.androidremotecontrolmcp.services.assistant.AssistantLocalFirst
import com.danielealbano.androidremotecontrolmcp.services.assistant.ChatRouting
import com.danielealbano.androidremotecontrolmcp.services.assistant.LocalOutcome
import com.danielealbano.androidremotecontrolmcp.services.assistant.SetupProbe
import com.danielealbano.androidremotecontrolmcp.services.assistant.SetupState
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.chat.Attachment
import com.danielealbano.androidremotecontrolmcp.services.chat.AttachmentStore
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatApiResult
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBackend
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatMessage
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationCatalog
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationEditor
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationRef
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationStore
import com.danielealbano.androidremotecontrolmcp.services.chat.ForwardTarget
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelGateway
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpLoopbackClient
import com.danielealbano.androidremotecontrolmcp.services.chat.Presence
import com.danielealbano.androidremotecontrolmcp.services.chat.PresenceMonitor
import com.danielealbano.androidremotecontrolmcp.services.chat.QuotedMessage
import com.danielealbano.androidremotecontrolmcp.services.memory.MemorySelection
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteChats
import com.danielealbano.androidremotecontrolmcp.services.skill.RecordingSession
import com.danielealbano.androidremotecontrolmcp.services.skill.Skill
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillCoordinator
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRepository
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillStep
import com.danielealbano.androidremotecontrolmcp.services.speech.MessageReader
import com.danielealbano.androidremotecontrolmcp.services.speech.ReadingState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("NativeChatViewModel")
class NativeChatViewModelPrivacyTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val loopback = mockk<PhoneMcpLoopbackClient>(relaxed = true)
    private val gateway = mockk<ModelGateway>()
    private val store = mockk<ConversationStore>()
    private val catalog =
        mockk<ConversationCatalog> {
            every { revision } returns MutableStateFlow(0)
            every { takeJump() } returns null
        }
    private val editor by lazy { ConversationEditor(mockk(relaxed = true), characters) }
    private val attachments = mockk<AttachmentStore>()
    private val presence = mockk<PresenceMonitor>(relaxed = true)
    private val characters = mockk<CharacterRepository>(relaxed = true)
    private val localFirst = mockk<AssistantLocalFirst>()
    private val remoteChats =
        mockk<RemoteChats>(relaxed = true) {
            every { handles(any()) } returns false
            every { permissions } returns MutableStateFlow(emptyList())
        }
    private val reader =
        mockk<MessageReader>(relaxed = true) { every { state } returns MutableStateFlow(ReadingState()) }
    private val setupProbe = mockk<SetupProbe> { coEvery { check(any(), any()) } returns SetupState() }
    private val agentRuns = AgentRuns(AgentHistoryFile(File.createTempFile("agent-history", ".json")))
    private val agent =
        mockk<AgentRunner>(relaxed = true) {
            every { runs } returns agentRuns
            every { supplement(any()) } returns false
        }
    private val settings = mockk<AssistSettingsRepository>()
    private val recording = RecordingSession()
    private val savedSkills = MutableStateFlow(emptyList<Skill>())
    private val skillRepo =
        mockk<SkillRepository>(relaxed = true) {
            every { skills } returns savedSkills
            every { find(any()) } answers { savedSkills.value.firstOrNull { s -> s.name == firstArg<String>() } }
        }
    private val coordinator =
        mockk<SkillCoordinator>(relaxed = true) { every { running } returns MutableStateFlow(null) }
    private lateinit var viewModel: NativeChatViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { store.activeCharacter() } returns null
        coEvery { store.here() } returns ConversationRef("assistant", "main")
        coEvery { store.loadAt(any()) } returns emptyList()
        coEvery { store.systemPrompt(any(), any()) } returns "system"
        every { store.memoryUse } returns MemorySelection()
        coEvery { store.modelChoice() } returns ModelChoice.ON_DEVICE
        every { store.saveAt(any(), any()) } returns Unit
        coEvery { store.receive(any()) } answers { firstArg() }
        coEvery { store.draftKey() } returns "assistant/main"
        every { store.catalog } returns catalog
        every { store.drafts } returns mockk(relaxed = true)
        every { characters.activeId } returns MutableStateFlow(null)
        every { characters.characters } returns MutableStateFlow(emptyList())
        every { settings.assistantName } returns flowOf("小助手")
        every { settings.providers } returns flowOf(emptyList())
        coEvery { gateway.chat(any(), any(), any()) } returns ChatApiResult(ok = true, content = "好的")
        // The chat streams its replies; here a stream is the scripted one-shot reply delivered at once.
        coEvery { gateway.chatStream(any(), any(), any(), any()) } coAnswers {
            val whole = gateway.chat(firstArg(), secondArg(), thirdArg())
            if (whole.ok) arg<(String) -> Unit>(3)(whole.content)
            whole
        }
        every { presence.state } returns MutableStateFlow(emptyMap())
        every { presence.presenceOf(any()) } returns Presence.ONLINE
        val backend = ChatBackend(gateway, loopback, store, editor, attachments, presence)
        val skillDeps = SkillChatDeps(recording, mockk(relaxed = true), skillRepo, coordinator)
        val routing = ChatRouting(localFirst, agent, setupProbe, reader, remoteChats, mockk(relaxed = true))
        viewModel = NativeChatViewModel(backend, characters, routing, settings, skillDeps, dispatcher)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the context ring is filled in while the view model is being built`() {
        // The collectors started in init run at once here; they must find the tracker already assigned.
        assertTrue(viewModel.ui.value.contextUsage > 0f)
    }

    @Test
    fun `a typed vault sentence never reaches history or the model`() {
        coEvery { localFirst.handle(any()) } returns LocalOutcome.Handled(message = null)
        viewModel.setDraft("记住我的手机号 一三八一二三四五六七八")
        viewModel.send()
        verify(exactly = 0) { store.saveAt(any(), any()) }
        coVerify(exactly = 0) { gateway.chat(any(), any(), any()) }
        verify(exactly = 0) { loopback.wake(any(), any()) }
        assertEquals("", viewModel.ui.value.draft)
    }

    @Test
    fun `ordinary chat goes to the model with the system prompt and the reply is kept`() {
        coEvery { localFirst.handle(any()) } returns LocalOutcome.Forward(phoneControl = false)
        viewModel.setDraft("今天天气怎么样")
        viewModel.send()
        coVerify { gateway.chat(match { it.first().role == "system" && it.last().text == "今天天气怎么样" }, any(), any()) }
        assertEquals(
            listOf("user", "assistant"),
            viewModel.ui.value.messages
                .map { it.role },
        )
    }

    @Test
    fun `a reply carries the quote and clears it after sending`() {
        coEvery { localFirst.handle(any()) } returns LocalOutcome.Forward(phoneControl = false)
        viewModel.setDraft("第一句")
        viewModel.send()
        val first =
            viewModel.ui.value.messages
                .first()
        viewModel.act(MessageAction.Reply(first.id))
        assertEquals(QuotedMessage(first.id, "我", "第一句"), viewModel.ui.value.replyTo)

        viewModel.setDraft("回你")
        viewModel.send()
        val reply =
            viewModel.ui.value.messages
                .first { it.content == "回你" }
        assertEquals(first.id, reply.replyTo?.id)
        assertNull(viewModel.ui.value.replyTo)
    }

    @Test
    fun `a partial selection replaces the quote text`() {
        coEvery { localFirst.handle(any()) } returns LocalOutcome.Forward(phoneControl = false)
        viewModel.setDraft("前半句，后半句")
        viewModel.send()
        val first =
            viewModel.ui.value.messages
                .first()
        viewModel.act(MessageAction.Reply(first.id, excerpt = "后半句"))
        assertEquals(
            "后半句",
            viewModel.ui.value.replyTo
                ?.excerpt,
        )
    }

    @Test
    fun `an attachment alone can be sent and skips the local rules`() {
        val picture = Attachment(Attachment.IMAGE, "cat.jpg", "/tmp/cat.jpg", "image/jpeg")
        coEvery { attachments.importImage(any()) } returns Result.success(picture)
        viewModel.act(MessageAction.Attach(mockk(), image = true))
        assertEquals(picture, viewModel.ui.value.attachment)
        viewModel.send()
        coVerify(exactly = 0) { localFirst.handle(any()) }
        verify { store.saveAt(any(), match { list -> list.first().attachment == picture }) }
        assertTrue(
            viewModel.ui.value.messages
                .first()
                .attachment == picture,
        )
        assertNull(viewModel.ui.value.attachment)
    }

    @Test
    fun `delete removes the message and saves the rest`() {
        coEvery { localFirst.handle(any()) } returns LocalOutcome.Forward(phoneControl = false)
        viewModel.setDraft("留着")
        viewModel.send()
        val user =
            viewModel.ui.value.messages
                .first()
        viewModel.act(MessageAction.Delete(user.id))
        assertEquals(
            listOf("assistant"),
            viewModel.ui.value.messages
                .map { it.role },
        )
        verify { store.saveAt(any(), match { list -> list.none { it.id == user.id } }) }
    }

    @Test
    fun `a model error sends the contact away and a reply brings it back`() {
        coEvery { localFirst.handle(any()) } returns LocalOutcome.Forward(phoneControl = false)
        coEvery { gateway.chat(any(), any(), any()) } returns ChatApiResult(ok = false, error = "HTTP 500: boom")
        viewModel.setDraft("在吗")
        viewModel.send()
        verify { presence.markFailed(ModelChoice.ON_DEVICE) }
        coEvery { gateway.chat(any(), any(), any()) } returns ChatApiResult(ok = true, content = "在")
        viewModel.setDraft("在吗")
        viewModel.send()
        verify { presence.markAlive(ModelChoice.ON_DEVICE) }
    }

    @Test
    fun `forward targets exclude the current conversation`() {
        assertEquals(emptyList<ForwardTarget>(), viewModel.ui.value.forwardTargets)
    }

    @Test
    fun `during a review the skill goes to the model as hidden context and only the reply is kept`() =
        runTest {
            val skill =
                Skill(
                    name = "录制的操作",
                    steps = listOf(SkillStep.Launch("com.android.settings"), SkillStep.Back, SkillStep.Back),
                )
            savedSkills.value = listOf(skill)
            recording.offerReview(skill.name)
            val sent = slot<List<ChatMessage>>()
            coEvery { gateway.chat(capture(sent), any(), any()) } returns ChatApiResult(ok = true, content = "好的")
            viewModel.skills.checkReview()
            assertEquals("录制的操作", viewModel.ui.value.skill.reviewing)
            assertTrue(
                sent.captured
                    .last()
                    .text
                    .contains("刚手动录好了一段手机操作"),
            )

            coEvery { localFirst.handle(any()) } returns LocalOutcome.Forward(phoneControl = true)
            viewModel.skills.focus(skill.name, 1)
            viewModel.setDraft("把第二步删掉")
            viewModel.send()
            // Sounded like a phone command, but a review keeps it with the model, with the skill attached.
            coVerify(exactly = 0) { loopback.wake(any(), any()) }
            assertTrue(
                sent.captured
                    .last()
                    .text
                    .contains("第 2 步"),
            )
            val shown = viewModel.ui.value.messages
            assertTrue(shown.any { it.content == "（针对第 2 步）把第二步删掉" })
            assertTrue(shown.none { it.content.contains("系统说明") || it.content.contains("系统通知") })
            assertEquals(null, viewModel.ui.value.skill.focus)
        }
}
