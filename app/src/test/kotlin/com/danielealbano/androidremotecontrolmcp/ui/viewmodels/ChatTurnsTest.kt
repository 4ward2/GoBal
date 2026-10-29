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
import com.danielealbano.androidremotecontrolmcp.services.chat.AttachmentStore
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatApiResult
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBackend
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBubble
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatMessage
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationCatalog
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationEditor
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationRef
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationStore
import com.danielealbano.androidremotecontrolmcp.services.chat.DraftStore
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelGateway
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpLoopbackClient
import com.danielealbano.androidremotecontrolmcp.services.chat.Presence
import com.danielealbano.androidremotecontrolmcp.services.chat.PresenceMonitor
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryEntry
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryPick
import com.danielealbano.androidremotecontrolmcp.services.memory.MemorySelection
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteChats
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteContact
import com.danielealbano.androidremotecontrolmcp.services.skill.RecordingSession
import com.danielealbano.androidremotecontrolmcp.services.skill.Skill
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillCoordinator
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRepository
import com.danielealbano.androidremotecontrolmcp.services.speech.MessageReader
import com.danielealbano.androidremotecontrolmcp.services.speech.ReadingState
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatItem
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatPresentation
import com.danielealbano.androidremotecontrolmcp.ui.chat.MemoryNote
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/** A scripted model: writes [deltas], then waits at [gate] (when set) before finishing with [result]. */
private class ScriptedReply(
    val deltas: List<String>,
    val result: ChatApiResult = ChatApiResult(ok = true, content = deltas.joinToString("")),
    val gate: CompletableDeferred<Unit>? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Chat turns: streaming, stop, edit, regenerate, context marks")
class ChatTurnsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = mockk<ModelGateway>()
    private val store = mockk<ConversationStore>()
    private val revision = MutableStateFlow(0)
    private var jump: String? = null
    private val catalog =
        mockk<ConversationCatalog> {
            every { revision } returns this@ChatTurnsTest.revision
            every { takeJump() } answers { jump.also { jump = null } }
        }
    private var stored = emptyList<ChatBubble>()
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
    private val saves = mutableListOf<List<ChatBubble>>()
    private val asked = mutableListOf<List<ChatMessage>>()
    private var script = ScriptedReply(listOf("好的"))
    private var storedDraft = ""
    private val draftStore =
        mockk<DraftStore>(relaxed = true) { every { load("assistant/main") } answers { storedDraft } }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { store.activeCharacter() } returns null
        coEvery { store.here() } returns ConversationRef("assistant", "main")
        coEvery { store.loadAt(any()) } answers { stored }
        coEvery { store.systemPrompt(any(), any()) } returns "system"
        every { store.memoryUse } returns MemorySelection()
        coEvery { store.modelChoice() } returns ModelChoice.ON_DEVICE
        every { store.saveAt(any(), any()) } answers { saves += secondArg<List<ChatBubble>>() }
        coEvery { store.receive(any()) } answers { firstArg() }
        coEvery { store.draftKey() } returns "assistant/main"
        every { store.catalog } returns catalog
        every { store.drafts } returns draftStore
        every { characters.activeId } returns MutableStateFlow(null)
        every { characters.characters } returns MutableStateFlow(emptyList())
        every { settings.assistantName } returns flowOf("小助手")
        every { settings.providers } returns flowOf(emptyList())
        every { presence.state } returns MutableStateFlow(emptyMap())
        every { presence.presenceOf(any()) } returns Presence.ONLINE
        coEvery { localFirst.handle(any()) } returns LocalOutcome.Forward(phoneControl = false)
        coEvery { gateway.chatStream(any(), any(), any(), any()) } coAnswers {
            asked += firstArg<List<ChatMessage>>()
            val current = script
            current.deltas.forEach { arg<(String) -> Unit>(3)(it) }
            current.gate?.await()
            current.result
        }
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): NativeChatViewModel {
        val skillRepo =
            mockk<SkillRepository>(relaxed = true) { every { skills } returns MutableStateFlow(emptyList<Skill>()) }
        val coordinator =
            mockk<SkillCoordinator>(relaxed = true) { every { running } returns MutableStateFlow(null) }
        val editor = ConversationEditor(mockk(relaxed = true), characters)
        val backend = ChatBackend(gateway, mockk(relaxed = true), store, editor, mockk<AttachmentStore>(), presence)
        val skillDeps = SkillChatDeps(RecordingSession(), mockk(relaxed = true), skillRepo, coordinator)
        val routing = ChatRouting(localFirst, agent, setupProbe, reader, remoteChats, mockk(relaxed = true))
        return NativeChatViewModel(backend, characters, routing, settings, skillDeps, dispatcher)
    }

    private fun NativeChatViewModel.say(text: String) {
        setDraft(text)
        send()
    }

    private fun NativeChatViewModel.shown(): List<String> = ui.value.messages.map { "${it.role}:${it.content}" }

    @Test
    fun `the reply is shown as it is written and becomes a message when it ends`() {
        val gate = CompletableDeferred<Unit>()
        script = ScriptedReply(listOf("你", "好"), gate = gate)
        val vm = viewModel()
        vm.say("在吗")
        assertEquals("你好", vm.ui.value.streaming)
        assertTrue(vm.ui.value.sending)
        assertEquals(listOf("user:在吗"), vm.shown())
        gate.complete(Unit)
        assertNull(vm.ui.value.streaming)
        assertFalse(vm.ui.value.sending)
        assertEquals(listOf("user:在吗", "assistant:你好"), vm.shown())
    }

    @Test
    fun `the model's thinking is kept with its answer and never sent back`() {
        script = ScriptedReply(listOf("好"), ChatApiResult(ok = true, content = "好", reasoning = " 先想了想 "))
        val vm = viewModel()
        vm.say("在吗")
        assertEquals(
            "先想了想",
            vm.ui.value.messages
                .last()
                .reasoning,
        )
        script = ScriptedReply(listOf("在"))
        vm.say("还在吗")
        assertFalse(asked.last().any { it.text.contains("先想了想") })
    }

    @Test
    fun `an answer keeps which memories it was given, and how many were left out`() {
        val used = MemoryPick(MemoryEntry("m1", "饮食"), "妈妈不吃香菜", tokens = 6)
        every { store.memoryUse } returns MemorySelection(listOf(used), listOf(MemoryEntry("m2", "房子")), "x")
        val vm = viewModel()
        vm.say("妈妈吃香菜吗")
        val answer =
            vm.ui.value.messages
                .last()
        assertEquals(listOf("m1") to 1, answer.memories to answer.memoriesLeft)
        coVerify { store.systemPrompt("妈妈吃香菜吗") }
        val note = ChatPresentation.toItems(vm.ui.value.messages).last() as ChatItem.AssistantMessage
        assertEquals(MemoryNote(listOf("m1"), left = 1), note.memory)
    }

    @Test
    fun `an agent on a computer is given no memory, and none is claimed under its answer`() {
        coEvery { store.modelChoice() } returns RemoteContact("pc", "agent").choice()
        every { store.memoryUse } returns MemorySelection(listOf(MemoryPick(MemoryEntry("m1", "饮食"), "x", tokens = 1)))
        val vm = viewModel()
        vm.say("打开设置")
        val answer =
            vm.ui.value.messages
                .last()
        assertTrue(answer.memories.isEmpty())
        coVerify(exactly = 0) { store.systemPrompt(any(), sending = true) }
        assertTrue(asked.last().none { it.role == "system" && it.text.isNotBlank() })
    }

    @Test
    fun `a half-written status tag never reaches the screen`() {
        script = ScriptedReply(listOf("哼", "<status>生闷"), gate = CompletableDeferred())
        val vm = viewModel()
        vm.say("小鱼干被我吃了")
        assertEquals("哼", vm.ui.value.streaming)
    }

    @Test
    fun `stop keeps what was written and ignores whatever arrives afterwards`() {
        val gate = CompletableDeferred<Unit>()
        script = ScriptedReply(listOf("第一句。"), ChatApiResult(ok = true, content = "第一句。第二句。"), gate)
        val vm = viewModel()
        vm.say("讲个故事")
        vm.stop()
        assertFalse(vm.ui.value.sending)
        assertNull(vm.ui.value.streaming)
        assertEquals(listOf("user:讲个故事", "assistant:第一句。"), vm.shown())
        assertEquals(listOf("user", "assistant"), saves.last().map { it.role })
        gate.complete(Unit)
        assertEquals(listOf("user:讲个故事", "assistant:第一句。"), vm.shown())
    }

    @Test
    fun `stop before the first word leaves only the question, and stopping twice is harmless`() {
        script = ScriptedReply(emptyList(), gate = CompletableDeferred())
        val vm = viewModel()
        vm.say("在吗")
        vm.stop()
        vm.stop()
        assertEquals(listOf("user:在吗"), vm.shown())
        assertFalse(vm.ui.value.sending)
        assertNull(vm.ui.value.error)
    }

    @Test
    fun `stop does nothing when no reply is being written`() {
        val vm = viewModel()
        vm.say("在吗")
        vm.stop()
        assertEquals(listOf("user:在吗", "assistant:好的"), vm.shown())
    }

    @Test
    fun `editing a question sends it again and drops what followed`() {
        val vm = viewModel()
        vm.say("问一")
        vm.say("问二")
        val first =
            vm.ui.value.messages
                .first()
        script = ScriptedReply(listOf("新回答"))
        vm.act(MessageAction.EditResend(first.id, " 改过的问一 "))
        assertEquals(listOf("user:改过的问一", "assistant:新回答"), vm.shown())
        assertEquals(listOf("system", "改过的问一"), asked.last().map { it.text })
        assertEquals(listOf("改过的问一", "新回答"), saves.last().map { it.content })
    }

    @Test
    fun `an edited sentence that the local rules take never changes the conversation`() {
        val vm = viewModel()
        vm.say("问一")
        val first =
            vm.ui.value.messages
                .first()
        coEvery { localFirst.handle(any()) } returns LocalOutcome.Handled(message = null)
        vm.act(MessageAction.EditResend(first.id, "记住我的手机号 一三八"))
        assertEquals(listOf("user:问一", "assistant:好的"), vm.shown())
        assertEquals(1, asked.size)
    }

    @Test
    fun `regenerating replaces the reply with a new one`() {
        val vm = viewModel()
        vm.say("讲个笑话")
        val reply =
            vm.ui.value.messages
                .last()
        script = ScriptedReply(listOf("另一个笑话"))
        vm.act(MessageAction.Regenerate(reply.id))
        assertEquals(listOf("user:讲个笑话", "assistant:另一个笑话"), vm.shown())
        assertEquals(listOf("system", "讲个笑话"), asked.last().map { it.text })
    }

    @Test
    fun `a failed regeneration brings the old reply back and saves nothing`() {
        val vm = viewModel()
        vm.say("讲个笑话")
        val reply =
            vm.ui.value.messages
                .last()
        val savedBefore = saves.size
        script = ScriptedReply(emptyList(), ChatApiResult(ok = false, error = "HTTP 500: boom"))
        vm.act(MessageAction.Regenerate(reply.id))
        assertEquals(listOf("user:讲个笑话", "assistant:好的"), vm.shown())
        assertEquals(savedBefore, saves.size)
        assertTrue(
            vm.ui.value.error
                .orEmpty()
                .isNotEmpty(),
        )
    }

    @Test
    fun `stopping a regeneration before any word brings the old reply back`() {
        val vm = viewModel()
        vm.say("讲个笑话")
        val reply =
            vm.ui.value.messages
                .last()
        script = ScriptedReply(emptyList(), gate = CompletableDeferred())
        vm.act(MessageAction.Regenerate(reply.id))
        assertEquals(listOf("user:讲个笑话"), vm.shown())
        vm.stop()
        assertEquals(listOf("user:讲个笑话", "assistant:好的"), vm.shown())
    }

    @Test
    fun `a question left out of the context takes its answer along, is saved, and is not sent again`() {
        val vm = viewModel()
        vm.say("问一")
        val first =
            vm.ui.value.messages
                .first()
        vm.act(MessageAction.SetExcluded(setOf(first.id), excluded = true))
        assertEquals(
            listOf(true, true),
            vm.ui.value.messages
                .map { it.excluded },
        )
        assertEquals(listOf(true, true), saves.last().map { it.excluded })
        vm.say("问二")
        assertEquals(listOf("system", "问二"), asked.last().map { it.text })
        coVerify(exactly = 2) { gateway.chatStream(any(), any(), any(), any()) }
    }

    @Test
    fun `opening another conversation shows its messages and the message to jump to`() {
        val vm = viewModel()
        vm.say("第一段的话")
        stored = listOf(ChatBubble("user", "另一段", id = "x1"), ChatBubble("assistant", "回答", id = "x2"))
        jump = "x2"
        revision.value += 1
        assertEquals(listOf("user:另一段", "assistant:回答"), vm.shown())
        assertEquals("x2", vm.ui.value.pendingJump)
        vm.act(MessageAction.JumpDone)
        assertNull(vm.ui.value.pendingJump)
    }

    @Test
    fun `a reply still on its way when the conversation changes is neither shown nor saved there`() {
        val gate = CompletableDeferred<Unit>()
        script = ScriptedReply(listOf("写到一半"), gate = gate)
        val vm = viewModel()
        vm.say("讲个故事")
        val savedBefore = saves.size
        stored = listOf(ChatBubble("user", "另一段", id = "x1"))
        revision.value += 1
        assertFalse(vm.ui.value.sending)
        assertNull(vm.ui.value.streaming)
        gate.complete(Unit)
        assertEquals(listOf("user:另一段"), vm.shown())
        assertEquals(savedBefore, saves.size)
    }

    @Test
    fun `sending forgets the saved draft, so it does not come back when the chat reopens`() {
        val vm = viewModel()
        vm.say("在吗")
        verify { draftStore.save("assistant/main", "") }
    }

    @Test
    fun `a send that cannot go out leaves the saved draft alone`() {
        val vm = viewModel()
        vm.send()
        verify(exactly = 0) { draftStore.save(any(), "") }
    }

    @Test
    fun `reading a message aloud hands its text to the voice`() {
        val vm = viewModel()
        vm.say("在吗")
        val reply =
            vm.ui.value.messages
                .last()
        vm.act(MessageAction.ReadAloud(reply.id))
        verify { reader.toggle(reply.id, "好的", 0) }
    }

    @Test
    fun `the draft left in a conversation is back when the chat opens`() {
        storedDraft = "写到一半的话"
        assertEquals("写到一半的话", viewModel().ui.value.draft)
    }
}
