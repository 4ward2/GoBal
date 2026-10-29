package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRunner
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBackend
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBubble
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationEdits
import com.danielealbano.androidremotecontrolmcp.services.chat.ForwardTarget
import com.danielealbano.androidremotecontrolmcp.services.chat.MarkdownText
import com.danielealbano.androidremotecontrolmcp.services.chat.OutgoingMessages
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryLibrary
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryRepository
import com.danielealbano.androidremotecontrolmcp.services.memory.MemorySource
import com.danielealbano.androidremotecontrolmcp.services.memory.NewMemory
import com.danielealbano.androidremotecontrolmcp.services.remote.PendingPermission
import com.danielealbano.androidremotecontrolmcp.services.remote.PermissionReply
import com.danielealbano.androidremotecontrolmcp.services.speech.MessageReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val REMEMBERED = "记下了，可以在 设置 → 记忆库 里查看和修改"

/** What the user can do to one message, or to the composer's pending reply / attachment. */
sealed interface MessageAction {
    /** Quote [id] for the next message; [excerpt] replaces the default quote text (partial selection). */
    data class Reply(
        val id: String,
        val excerpt: String? = null,
    ) : MessageAction

    data object ClearReply : MessageAction

    data class Attach(
        val uri: Uri,
        val image: Boolean,
    ) : MessageAction

    data object RemoveAttachment : MessageAction

    data class Delete(
        val id: String,
    ) : MessageAction

    data class Forward(
        val id: String,
        val target: ForwardTarget,
    ) : MessageAction

    data class Highlight(
        val id: String?,
    ) : MessageAction

    /** Replace the user's message [id] with [text] and send again from there. */
    data class EditResend(
        val id: String,
        val text: String,
    ) : MessageAction

    /** Answer again in place of the reply [id]. */
    data class Regenerate(
        val id: String,
    ) : MessageAction

    /** Read the message [id] aloud, or stop when it is the one being read. */
    data class ReadAloud(
        val id: String,
    ) : MessageAction

    /** Keep what the message [id] says as a memory of the current contact. */
    data class Remember(
        val id: String,
    ) : MessageAction

    /** The user's answer to what an agent on a computer asked leave for. */
    data class AnswerPermission(
        val pending: PendingPermission,
        val reply: PermissionReply,
    ) : MessageAction

    /** Stop the phone task that is running. */
    data object StopTask : MessageAction

    /** The user carries on by hand from where the phone task is. */
    data object TakeOverTask : MessageAction

    /** The screen has scrolled to the message a search result asked for. */
    data object JumpDone : MessageAction

    /** Leave [ids] out of what the model is sent ([excluded]) or put them back. */
    data class SetExcluded(
        val ids: Set<String>,
        val excluded: Boolean,
    ) : MessageAction
}

/**
 * Applies [MessageAction]s to the chat state on behalf of [NativeChatViewModel]: reply quotes,
 * attachments, deletion and forwarding. Kept out of the view model so each stays small.
 *
 * @param assistantName resolves the assistant's current name (for "转发自" labels).
 */
class ChatActionHandler(
    private val backend: ChatBackend,
    private val ui: MutableStateFlow<NativeChatUiState>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val assistantName: suspend () -> String,
) {
    /** Sending, editing and regenerating; set by the view model once both exist. */
    internal var turns: ChatTurns? = null

    /** The memory library; set by the view model. */
    internal var memory: MemoryLibrary? = null

    /** The link to agents on paired computers; set by the view model. */
    internal var remote: RemoteChatSync? = null

    /** The voice that reads messages aloud; set by the view model. */
    internal var reader: MessageReader? = null

    /** The phone agent, for the task card's buttons; set by the view model. */
    internal var agent: AgentRunner? = null

    /** Recomputes the context ring after messages were left out or put back. */
    internal var onContextChanged: () -> Unit = {}

    fun handle(action: MessageAction) {
        when (action) {
            is MessageAction.Reply -> reply(action)
            MessageAction.ClearReply -> ui.update { it.copy(replyTo = null) }
            is MessageAction.Attach -> attach(action)
            MessageAction.RemoveAttachment -> ui.update { it.copy(attachment = null) }
            is MessageAction.Delete -> delete(action.id)
            is MessageAction.Forward -> forward(action)
            is MessageAction.Highlight -> ui.update { it.copy(highlightId = action.id) }
            is MessageAction.EditResend -> turns?.editAndResend(action.id, action.text)
            is MessageAction.Regenerate -> turns?.regenerate(action.id)
            is MessageAction.SetExcluded -> setExcluded(action)
            MessageAction.JumpDone -> ui.update { it.copy(pendingJump = null) }
            is MessageAction.ReadAloud -> readAloud(action.id)
            else -> steer(action)
        }
    }

    /**
     * Writes the message down as a memory of whoever the chat is with, noting which message of
     * which conversation it came from. A message kept out of the context is not taken.
     */
    private fun remember(id: String) {
        val state = ui.value
        val bubble = state.messages.firstOrNull { it.id == id && !it.excluded } ?: return
        val at = state.conversation
        scope.launch(ioDispatcher) {
            val owner = at?.owner ?: MemoryRepository.ASSISTANT
            val source = MemorySource(MemorySource.CHAT, owner, at?.id.orEmpty(), id)
            val kept = memory?.add(NewMemory(MarkdownText.plain(bubble.content), scope = owner, source = source))
            val told = kept?.exceptionOrNull()?.message ?: REMEMBERED
            ui.update { it.copy(error = told) }
        }
    }

    /** The buttons on a task card and on an agent's permission card. */
    private fun steer(action: MessageAction) {
        when (action) {
            is MessageAction.AnswerPermission -> remote?.answer(action.pending, action.reply)
            is MessageAction.Remember -> remember(action.id)
            MessageAction.StopTask -> agent?.stop()
            else -> agent?.takeOver()
        }
    }

    /** Each character keeps one voice, the same one it speaks with on a call. */
    private fun readAloud(id: String) {
        val state = ui.value
        val bubble = state.messages.firstOrNull { it.id == id } ?: return
        val voice = state.character?.let { abs(it.id.hashCode()) } ?: 0
        reader?.toggle(id, bubble.content, voice)
    }

    private fun setExcluded(action: MessageAction.SetExcluded) {
        if (ui.value.sending) return
        scope.launch(ioDispatcher) {
            val state = ui.value
            val next = ConversationEdits.setExcluded(state.messages, action.ids, action.excluded)
            backend.keep(state.conversation, next)
            ui.updateAt(state.conversation) { it.copy(messages = next) }
            onContextChanged()
        }
    }

    private fun reply(action: MessageAction.Reply) {
        val state = ui.value
        val bubble = state.messages.firstOrNull { it.id == action.id } ?: return
        val quote = backend.editor.quote(bubble, speakerOf(bubble, state.character?.name.orEmpty()))
        val excerpt = action.excerpt?.let(OutgoingMessages::shorten) ?: quote.excerpt
        ui.update { it.copy(replyTo = quote.copy(excerpt = excerpt)) }
    }

    private fun attach(action: MessageAction.Attach) {
        if (ui.value.attaching) return
        ui.update { it.copy(attaching = true, error = null) }
        scope.launch(ioDispatcher) {
            val store = backend.attachments
            val imported = if (action.image) store.importImage(action.uri) else store.importDocument(action.uri)
            imported.fold(
                onSuccess = { attachment -> ui.update { it.copy(attaching = false, attachment = attachment) } },
                onFailure = { e -> ui.update { it.copy(attaching = false, error = e.message ?: "无法读取文件") } },
            )
        }
    }

    private fun delete(id: String) {
        scope.launch(ioDispatcher) {
            val state = ui.value
            val next = backend.editor.delete(state.messages, id)
            backend.keep(state.conversation, next)
            ui.updateAt(state.conversation) { it.copy(messages = next) }
        }
    }

    private fun forward(action: MessageAction.Forward) {
        val state = ui.value
        val bubble = state.messages.firstOrNull { it.id == action.id } ?: return
        scope.launch(ioDispatcher) {
            val from = speakerOf(bubble, state.character?.name ?: assistantName())
            backend.editor.forward(bubble, from, action.target)
        }
    }

    /** Who wrote a bubble, as the quote / forward label shows it. */
    private fun speakerOf(
        bubble: ChatBubble,
        assistantSide: String,
    ): String = if (bubble.role == "user") "我" else assistantSide
}
