package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.services.assistant.ChatRouting
import com.danielealbano.androidremotecontrolmcp.services.assistant.LocalOutcome
import com.danielealbano.androidremotecontrolmcp.services.chat.Attachment
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatApiResult
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBackend
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBubble
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationEdits
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationRef
import com.danielealbano.androidremotecontrolmcp.services.chat.OutgoingMessages
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneHandoff
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpChatLogic
import com.danielealbano.androidremotecontrolmcp.services.chat.QuotedMessage
import com.danielealbano.androidremotecontrolmcp.services.chat.RootHelper
import com.danielealbano.androidremotecontrolmcp.services.chat.StreamingText
import com.danielealbano.androidremotecontrolmcp.services.memory.MemorySelection
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteContact
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** What a turn needs besides the chat state: the local rules, the skill review and the context ring. */
internal class TurnDeps(
    val backend: ChatBackend,
    val routing: ChatRouting,
    val skills: SkillChat,
    val context: ContextUsageTracker,
)

/** A message on its way out: what precedes it and what it carries. */
private data class Outgoing(
    val base: List<ChatBubble>,
    val text: String,
    val attachment: Attachment?,
    val replyTo: QuotedMessage?,
    /** True for the composer's draft (cleared once sent); false for an edited earlier message. */
    val fromComposer: Boolean,
    /** The conversation it is said in. */
    val at: ConversationRef?,
)

/**
 * One exchange with the model on behalf of [NativeChatViewModel]: sending, streaming the reply
 * into the conversation as it is written, stopping it, editing an earlier question and asking
 * for an answer again.
 */
internal class ChatTurns(
    private val deps: TurnDeps,
    private val ui: MutableStateFlow<NativeChatUiState>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val backend = deps.backend

    /** Bumped by every reply that starts and by [stop]: a reply that is no longer current is ignored. */
    private val turn = AtomicInteger()
    private var job: Job? = null

    /**
     * Local rules first ([AssistantLocalFirst]): vault sentences are confirmed on the phone and
     * never enter the conversation; the rest goes to the phone agent or the language model.
     * A message with an attachment always goes to the model.
     */
    fun send() {
        val state = ui.value
        val text = state.draft.trim()
        val nothingToSend = text.isEmpty() && state.attachment == null
        if (nothingToSend || state.sending || state.attaching) return
        val at = state.conversation
        val outgoing = Outgoing(state.messages, text, state.attachment, state.replyTo, fromComposer = true, at = at)
        job = scope.launch(ioDispatcher) { submit(outgoing) }
    }

    /** Replaces the user's message [id] with [text] and sends from there; later messages are dropped. */
    fun editAndResend(
        id: String,
        text: String,
    ) {
        val state = ui.value
        val rewind = ConversationEdits.forEdit(state.messages, id)?.takeUnless { state.sending } ?: return
        val clean = text.trim()
        val target = rewind.target
        if (clean.isEmpty() && target.attachment == null) return
        val at = state.conversation
        val outgoing = Outgoing(rewind.kept, clean, target.attachment, target.replyTo, fromComposer = false, at = at)
        job = scope.launch(ioDispatcher) { submit(outgoing) }
    }

    /**
     * Answers again in place of the reply [id]. The old reply (and anything after it) leaves the
     * screen at once but is only discarded when the new one arrives: an error or an empty stop
     * brings it back.
     */
    fun regenerate(id: String) {
        val state = ui.value
        val rewind = ConversationEdits.forRegenerate(state.messages, id)
        if (rewind == null || state.sending) return
        job =
            scope.launch(ioDispatcher) {
                ui.update { it.copy(messages = rewind.kept, sending = true, error = null) }
                toModel(rewind.kept, fallback = state.messages, at = state.conversation)
            }
    }

    /** Stops the reply being written; what has arrived so far stays as the answer. */
    fun stop() {
        val mine = turn.get()
        // The reply and the stop both end the turn; whoever takes it first writes the answer, once.
        val taken = ui.value.streaming != null && turn.compareAndSet(mine, mine + 1)
        if (!taken) return
        val before = ui.getAndUpdate { it.copy(streaming = null, sending = false) }
        job?.cancel()
        val kept = before.streaming.orEmpty().trim()
        val at = before.conversation
        val fallback = restore
        restore = null
        scope.launch(ioDispatcher) {
            if (kept.isEmpty()) {
                fallback?.let { old -> ui.updateAt(at) { it.copy(messages = old) } }
            } else {
                val next = before.messages + PhoneMcpChatLogic.newBubble(role = "assistant", content = kept)
                backend.keep(at, next)
                ui.updateAt(at) { it.copy(messages = next) }
            }
            deps.context.update()
        }
    }

    /**
     * The conversation on screen is being replaced by another: a reply still on its way belongs
     * to the old one and must neither show up in nor be saved to the new one.
     */
    fun abandon() {
        turn.incrementAndGet()
        job?.cancel()
        restore = null
        ui.update { it.copy(sending = false, streaming = null) }
    }

    /** The conversation to put back when a regeneration produces nothing. */
    private var restore: List<ChatBubble>? = null

    private suspend fun submit(outgoing: Outgoing) {
        val state = ui.value
        val text = outgoing.text
        val attachment = outgoing.attachment
        val local =
            if (attachment == null) deps.routing.localFirst.handle(text) else LocalOutcome.Forward(phoneControl = false)
        if (local is LocalOutcome.Handled) {
            ui.update { it.copy(draft = if (outgoing.fromComposer) "" else it.draft, error = local.message) }
            return
        }
        // While a recorded skill is reviewed, words go to the agent with the skill as context,
        // even when they sound like a phone command ("把第二步删掉").
        val reviewing = state.skill.reviewing != null && attachment == null
        val focus = state.skill.focus?.takeIf { reviewing }
        val shown = if (focus != null) "（针对第 ${focus + 1} 步）$text" else text
        val user =
            PhoneMcpChatLogic
                .newBubble(role = "user", content = shown)
                .copy(replyTo = outgoing.replyTo, attachment = attachment)
        val pending = outgoing.base + user
        ui.update { it.afterSending(pending, outgoing.fromComposer) }
        backend.keep(outgoing.at, pending)
        // An agent on a computer is talked to, whatever the words: "打开设置" is for it, not for this phone.
        val toComputer = RemoteContact.of(backend.store.modelChoice()) != null
        val phoneControl = !toComputer && (local as? LocalOutcome.Forward)?.phoneControl == true
        // While the phone agent works, what the user types is for it ("补充"), not a new question.
        val added = !reviewing && attachment == null && deps.routing.agent.supplement(text)
        when {
            reviewing -> deps.skills.replyInReview(pending)
            added -> ui.update { it.copy(sending = false) }
            phoneControl -> toAgent(text, pending, outgoing.at)
            else -> toModel(pending, fallback = null, at = outgoing.at)
        }
    }

    /**
     * Hands a phone task to the in-app agent, which thinks with the contact's model (the phone's
     * default one when none is chosen).
     */
    private suspend fun toAgent(
        text: String,
        pending: List<ChatBubble>,
        at: ConversationRef?,
    ) {
        val noModel = backend.store.modelChoice().onDevice
        val started = PhoneTasks.start(noModel, RootHelper.present()) { deps.routing.agent.start(text) }
        val next = pending + started.bubble
        backend.keep(at, next)
        ui.updateAt(at) { it.copy(messages = next, sending = false, error = started.error) }
    }

    /**
     * Streams the model's answer to [pending] into the state. [fallback] is the conversation to
     * return to when nothing comes of it (a regeneration that failed).
     */
    private suspend fun toModel(
        pending: List<ChatBubble>,
        fallback: List<ChatBubble>?,
        at: ConversationRef?,
    ) {
        val mine = turn.incrementAndGet()
        restore = fallback
        ui.update { it.copy(streaming = "") }
        val asked = pending.lastOrNull { it.role == "user" }?.content.orEmpty()
        val choice = backend.store.modelChoice()
        // An agent on a computer is sent the newest sentence and nothing of this phone's: no
        // memory is chosen for it, so none is marked as used and none is claimed under its answer.
        val toComputer = RemoteContact.of(choice) != null
        val system = if (toComputer) "" else backend.store.systemPrompt(asked)
        val messages = OutgoingMessages.toModelMessages(system, pending)
        val memory = if (toComputer) MemorySelection() else backend.store.memoryUse
        val written = StringBuilder()
        val result =
            runCatching {
                backend.gateway.chatStream(messages, choice) { delta ->
                    val shown = StreamingText.visible(written.append(delta).toString())
                    // A stopped reply has no bubble any more: a late piece must not bring one back.
                    ui.update { if (turn.get() == mine && it.streaming != null) it.copy(streaming = shown) else it }
                }
            }.getOrElse { ChatApiResult(ok = false, error = it.message.orEmpty()) }
        // Stopped meanwhile: stop() already kept what had been written.
        if (!turn.compareAndSet(mine, mine + 1)) return
        restore = null
        val handedOver = if (result.ok && !toComputer) PhoneHandoff.taskIn(result.content) else null
        if (handedOver != null) {
            // The model saw a phone request the local rules missed: the phone does it, in the user's words.
            backend.presence.markAlive(choice)
            ui.update { it.copy(streaming = null) }
            toAgent(asked, pending, at)
        } else if (result.ok) {
            backend.presence.markAlive(choice)
            val reply = backend.store.receive(result.content)
            val answer = PhoneMcpChatLogic.newBubble(role = "assistant", content = reply)
            val remembered = answer.copy(memories = memory.used.map { it.entry.id }, memoriesLeft = memory.left.size)
            val next = pending + remembered.copy(reasoning = result.reasoning.trim())
            backend.keep(at, next)
            ui.updateAt(at) { it.copy(messages = next, sending = false, streaming = null) }
            deps.context.update()
        } else {
            backend.presence.markFailed(choice)
            val error = PhoneMcpChatLogic.formatChatError(result.error.ifBlank { "empty" })
            ui.updateAt(at) {
                it.copy(messages = fallback ?: it.messages, sending = false, streaming = null, error = error)
            }
        }
    }

    private fun NativeChatUiState.afterSending(
        pending: List<ChatBubble>,
        fromComposer: Boolean,
    ): NativeChatUiState =
        copy(
            messages = pending,
            draft = if (fromComposer) "" else draft,
            sending = true,
            error = null,
            replyTo = if (fromComposer) null else replyTo,
            attachment = if (fromComposer) null else attachment,
        )
}
