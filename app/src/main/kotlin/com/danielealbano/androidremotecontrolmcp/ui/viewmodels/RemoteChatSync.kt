package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBackend
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationRef
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkState
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkStatus
import com.danielealbano.androidremotecontrolmcp.services.remote.PendingPermission
import com.danielealbano.androidremotecontrolmcp.services.remote.PermissionReply
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteChats
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteContact
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the chat shows about a contact that is an agent on a paired computer. */
data class RemoteView(
    val contact: RemoteContact,
    val link: LinkState = LinkState.CONNECTING,
    /** One line for under the contact's name. */
    val line: String = RemoteLines.CONNECTING,
    /** What the agent is waiting to be allowed to do, oldest first. */
    val permissions: List<PendingPermission> = emptyList(),
)

/** The words for where the link to a computer stands. Pure. */
object RemoteLines {
    const val CONNECTING = "正在连接电脑…"
    const val ONLINE = "电脑在线"
    const val UNBOUND = "这台电脑已解绑"

    fun of(status: LinkStatus): String =
        when (status.state) {
            LinkState.CONNECTING -> CONNECTING
            LinkState.ONLINE -> ONLINE
            LinkState.OFFLINE -> "电脑不在线，正在重试"
            LinkState.REJECTED -> "配对已失效"
        }
}

/**
 * Keeps the chat in step with an agent on a computer: the link's state under the name, the
 * conversation as the computer has it when the chat opens, and the agent's permission requests
 * as cards the user answers.
 */
internal class RemoteChatSync(
    private val backend: ChatBackend,
    private val remote: RemoteChats,
    private val ui: MutableStateFlow<NativeChatUiState>,
) {
    private var following: Job? = null

    fun observe(
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher,
    ) {
        scope.launch(ioDispatcher) {
            remote.permissions.collect { all -> ui.update { state -> state.withPermissions(all) } }
        }
    }

    /**
     * Called when a conversation has been put on screen. For an agent on a computer, the
     * computer's own record replaces the phone's copy: what was said to the agent from the
     * computer, or from another phone, is part of the conversation too.
     */
    suspend fun opened(
        at: ConversationRef,
        scope: CoroutineScope,
    ) {
        val choice = backend.store.modelChoice()
        val contact = RemoteContact.of(choice)
        following?.cancel()
        ui.update { it.copy(remote = contact?.let { c -> RemoteView(c) }?.withPermissions(remote.permissions.value)) }
        if (contact == null) return
        following = scope.launch { follow(choice, contact) }
        val theirs = remote.history(choice)
        // A message already on its way out is newer than what the computer has: leave the screen alone.
        if (theirs != null && !ui.value.sending) {
            backend.store.saveAt(at, theirs)
            ui.updateAt(at) { it.copy(messages = theirs) }
        }
    }

    private suspend fun follow(
        choice: ModelChoice,
        contact: RemoteContact,
    ) {
        val status = remote.status(choice)
        if (status == null) {
            val unbound: (RemoteView) -> RemoteView = { it.copy(link = LinkState.REJECTED, line = RemoteLines.UNBOUND) }
            ui.update { it.remoteIf(contact, unbound) }
            return
        }
        status.collect { now ->
            ui.update { it.remoteIf(contact) { view -> view.copy(link = now.state, line = RemoteLines.of(now)) } }
        }
    }

    /** The user tapped allow or deny on a card. */
    fun answer(
        pending: PendingPermission,
        reply: PermissionReply,
    ) {
        if (!remote.answer(pending, reply)) ui.update { it.copy(error = NOT_DELIVERED) }
    }

    private fun NativeChatUiState.withPermissions(all: List<PendingPermission>): NativeChatUiState =
        copy(remote = remote?.withPermissions(all))

    private fun RemoteView.withPermissions(all: List<PendingPermission>): RemoteView =
        copy(permissions = all.filter { it.contact == contact })

    private fun NativeChatUiState.remoteIf(
        contact: RemoteContact,
        change: (RemoteView) -> RemoteView,
    ): NativeChatUiState = if (remote?.contact == contact) copy(remote = change(remote)) else this

    private companion object {
        const val NOT_DELIVERED = "没能把你的决定送到电脑上，请等连上后再点一次。"
    }
}
