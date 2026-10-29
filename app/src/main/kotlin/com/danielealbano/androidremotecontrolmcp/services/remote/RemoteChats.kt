package com.danielealbano.androidremotecontrolmcp.services.remote

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatApiResult
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBubble
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A permission an agent is waiting for, with where it came from. */
data class PendingPermission(
    val contact: RemoteContact,
    val permission: RemotePermission,
)

/**
 * What agents are waiting to be allowed. Nothing leaves this list except by the user's answer
 * or the agent's own doing.
 */
class PermissionBoard {
    private val _pending = MutableStateFlow<List<PendingPermission>>(emptyList())
    val pending: StateFlow<List<PendingPermission>> = _pending.asStateFlow()

    fun ask(
        contact: RemoteContact,
        permission: RemotePermission,
    ) = _pending.update { known ->
        known.filterNot { it.permission.id == permission.id } + PendingPermission(contact, permission)
    }

    fun settle(permissionId: String) = _pending.update { known -> known.filterNot { it.permission.id == permissionId } }

    /** The agent's turn is over: whatever it had asked no longer waits. */
    fun forget(contact: RemoteContact) = _pending.update { known -> known.filterNot { it.contact == contact } }
}

/**
 * Talking to an agent on a paired computer as if it were a contact's model: one message goes
 * out, its answer streams back. The agent keeps its own conversation, instructions and tools on
 * the computer; nothing of this phone's (memory, vault, screen) is sent along, and nothing the
 * agent says can make the phone act.
 */
@Singleton
class RemoteChats
    @Inject
    constructor(
        private val computers: RemoteComputers,
    ) {
        private val board = PermissionBoard()

        /** What agents are waiting to be allowed, for the cards in their conversations. */
        val permissions: StateFlow<List<PendingPermission>> = board.pending

        fun handles(choice: ModelChoice): Boolean = RemoteContact.of(choice) != null

        /** Where the link to the contact's computer stands; null when the computer is no longer bound. */
        fun status(choice: ModelChoice): StateFlow<LinkStatus>? {
            val contact = RemoteContact.of(choice)
            return contact?.let { computers.session(it.computerId) }?.status
        }

        /** Sends [text] to the agent and streams its answer to [onDelta] until it stops. */
        suspend fun talk(
            choice: ModelChoice,
            text: String,
            images: List<File> = emptyList(),
            onDelta: (String) -> Unit,
        ): ChatApiResult {
            val contact = RemoteContact.of(choice)
            val session = contact?.let { computers.session(it.computerId) }
            val away = session?.takeUnless { online(it) }
            val reason = away?.status?.value?.reason
            val down = reason?.ifEmpty { OFFLINE }
            return when {
                contact == null || session == null -> ChatApiResult(ok = false, error = UNBOUND)
                down != null -> ChatApiResult(ok = false, error = down)
                else -> exchange(Exchange(session, contact, onDelta), text, images)
            }
        }

        private suspend fun online(session: AgentSession): Boolean {
            val settled =
                withTimeoutOrNull(CONNECT_WAIT_MS) {
                    session.status.first { it.state != LinkState.CONNECTING }
                }
            return settled?.state == LinkState.ONLINE
        }

        private suspend fun exchange(
            turn: Exchange,
            text: String,
            images: List<File>,
        ): ChatApiResult {
            val session = turn.session
            val agentId = turn.contact.agentId
            session.watch(listOf(agentId))
            return try {
                coroutineScope {
                    val sending =
                        launch(start = CoroutineStart.LAZY) { session.send(agentId, text, images).getOrThrow() }
                    // Either the agent's turn ends, or the link to the computer does: never wait on a dead line.
                    val lost = session.status.filter { it.state != LinkState.ONLINE }.map<LinkStatus, Any> { it }
                    val events = session.events.onSubscription { sending.start() }
                    val outcome = merge(events, lost).first { it is LinkStatus || closes(it as RemoteEvent, turn) }
                    check(outcome is RemoteEvent.TurnEnded) { DROPPED }
                    sending.join()
                    board.forget(turn.contact)
                    val reply = ChatApiResult(true, turn.answer.text(), reasoning = turn.answer.thought())
                    val failed = outcome.error.isNotEmpty() && reply.content.isBlank()
                    if (failed) ChatApiResult(ok = false, error = outcome.error) else reply
                }
            } catch (stopped: CancellationException) {
                // The user pressed stop: the agent on the computer is told to stop too.
                withContext(NonCancellable) { session.cancel(agentId) }
                throw stopped
            } catch (failed: IllegalStateException) {
                ChatApiResult(ok = false, error = failed.message ?: OFFLINE)
            }
        }

        /** Takes in one event; true when it ends this agent's turn. */
        private fun closes(
            event: RemoteEvent,
            turn: Exchange,
        ): Boolean {
            val mine = turn.contact.agentId
            when (event) {
                is RemoteEvent.Item -> if (event.agentId == mine) turn.took(event.item)
                is RemoteEvent.PermissionAsked -> if (event.agentId == mine) board.ask(turn.contact, event.permission)
                is RemoteEvent.PermissionSettled -> board.settle(event.permissionId)
                else -> Unit
            }
            return event is RemoteEvent.TurnEnded && event.agentId == mine
        }

        /** The user's answer, from a tap on the card. False when it could not be delivered. */
        fun answer(
            pending: PendingPermission,
            reply: PermissionReply,
        ): Boolean {
            val session = computers.session(pending.contact.computerId)
            val sent = session?.answer(pending.contact.agentId, pending.permission.id, reply) == true
            if (sent) board.settle(pending.permission.id)
            return sent
        }

        /** The agent's conversation as it stands on the computer, or null when it cannot be read now. */
        suspend fun history(choice: ModelChoice): List<ChatBubble>? {
            val contact = RemoteContact.of(choice)
            val session = contact?.let { computers.session(it.computerId) }?.takeIf { online(it) }
            val items = session?.history(contact.agentId)?.getOrNull()
            val agent = session?.agents?.value?.firstOrNull { it.id == contact.agentId }
            // What it had asked before this phone was looking still waits for an answer.
            agent?.pending?.forEach { board.ask(contact, it) }
            return items?.let(RemoteText::bubbles)
        }

        companion object {
            const val UNBOUND = "这台电脑已经解绑了，这个联系人不能再用。"
            const val OFFLINE = "连不上这台电脑。"
            const val DROPPED = "和电脑的连接断了。它那边可能还在继续，连上后重新打开这个对话就能看到。"
            private const val CONNECT_WAIT_MS = 15_000L
        }
    }

/** One message to an agent and the answer coming back. */
private class Exchange(
    val session: AgentSession,
    val contact: RemoteContact,
    private val onDelta: (String) -> Unit,
) {
    val answer = AnswerText()

    fun took(item: RemoteItem) {
        answer.add(item).takeIf { it.isNotEmpty() }?.let(onDelta)
    }
}
