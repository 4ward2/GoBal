package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.chat.Attachment
import com.danielealbano.androidremotecontrolmcp.services.remote.AgentCatalog
import com.danielealbano.androidremotecontrolmcp.services.remote.AgentSession
import com.danielealbano.androidremotecontrolmcp.services.remote.ChannelHooks
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkState
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkStatus
import com.danielealbano.androidremotecontrolmcp.services.remote.NewAgent
import com.danielealbano.androidremotecontrolmcp.services.remote.PairedComputer
import com.danielealbano.androidremotecontrolmcp.services.remote.Pairing
import com.danielealbano.androidremotecontrolmcp.services.remote.PermissionReply
import com.danielealbano.androidremotecontrolmcp.services.remote.REQUEST_TIMEOUT_MS
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteAgent
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteBackend
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteEvent
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteItem
import com.danielealbano.androidremotecontrolmcp.services.remote.SecureChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What a session needs to know about this phone. */
class ClientIdentity(
    /** Stable for this installation; the daemon tells clients apart by it. */
    val clientId: String,
    val appVersion: String,
)

/**
 * What a session knows from what the daemon has said so far: whether it has introduced itself,
 * its agents, and who is waiting for which answer. Frames go in, state comes out.
 */
internal class PaseoInbox(
    private val channel: SecureChannel,
    private val identity: ClientIdentity,
    private val expectedPeer: String,
) {
    val status = MutableStateFlow(LinkStatus(LinkState.CONNECTING))
    val computerName = MutableStateFlow("")
    val agents = MutableStateFlow<List<RemoteAgent>>(emptyList())
    val events = MutableSharedFlow<RemoteEvent>(extraBufferCapacity = BUFFERED)
    val pushes = MutableSharedFlow<PaseoFrame.Push>(extraBufferCapacity = BUFFERED)
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<PaseoFrame.Reply>>()

    /** The agents whose conversations are followed; asked for again after every reconnect. */
    @Volatile
    var watched: List<String> = emptyList()

    /** True once the daemon has said who it is: only then is anything asked of it. */
    @Volatile
    var greeted = false
        private set

    /** The channel being up is not yet being connected: the daemon first has to say who it is. */
    fun linkChanged(link: LinkStatus) {
        greeted = false
        if (link.state == LinkState.ONLINE) {
            channel.send(PaseoRequests.hello(identity.clientId, identity.appVersion))
            status.value = LinkStatus(LinkState.CONNECTING)
        } else {
            status.value = link
            val reason = link.reason.ifEmpty { PaseoSession.NOT_CONNECTED }
            waiting.values.forEach { it.complete(PaseoFrame.Reply("", reason)) }
            waiting.clear()
        }
    }

    /** Sends [frame] and waits for the answer to the request [id]; a silent computer is an answer too. */
    private suspend fun request(
        id: String,
        frame: String,
    ): PaseoFrame.Reply {
        val answer = CompletableDeferred<PaseoFrame.Reply>()
        waiting[id] = answer
        val heard = if (channel.send(frame)) withTimeoutOrNull(REQUEST_TIMEOUT_MS) { answer.await() } else null
        waiting.remove(id)
        return heard ?: PaseoFrame.Reply(id, PaseoSession.NO_ANSWER)
    }

    /** Asks the daemon something and gives its answer, or why there is none. */
    suspend fun ask(build: (String) -> String): Result<PaseoFrame.Reply> {
        val id = UUID.randomUUID().toString()
        val reply = if (greeted) request(id, build(id)) else null
        val down = status.value.reason
        val error = reply?.error ?: down.ifEmpty { PaseoSession.NOT_CONNECTED }
        val failed = Result.failure<PaseoFrame.Reply>(IllegalStateException(error))
        return if (reply != null && error.isEmpty()) Result.success(reply) else failed
    }

    fun read(frame: PaseoFrame) {
        when (frame) {
            is PaseoFrame.ServerInfo -> greetedBy(frame)
            is PaseoFrame.Reply -> answered(frame)
            is PaseoFrame.Live -> happened(frame.event)
            is PaseoFrame.Push -> pushes.tryEmit(frame)
            PaseoFrame.Other -> Unit
        }
    }

    /** Takes [agent] into the list (or out of it, once archived). */
    fun learned(agent: RemoteAgent) =
        agents.update { known ->
            val others = known.filterNot { it.id == agent.id }
            if (agent.archived) others else others + agent
        }

    fun forget(agentId: String) = agents.update { known -> known.filterNot { it.id == agentId } }

    private fun answered(reply: PaseoFrame.Reply) {
        if (reply.requestId == LIST_ON_CONNECT) {
            if (reply.error.isEmpty()) agents.value = reply.agents.filterNot { it.archived }
        } else {
            waiting.remove(reply.requestId)?.complete(reply)
        }
    }

    private fun greetedBy(info: PaseoFrame.ServerInfo) {
        if (expectedPeer.isNotEmpty() && info.serverId != expectedPeer) {
            status.value = LinkStatus(LinkState.REJECTED, PaseoSession.ANOTHER_COMPUTER)
            channel.close()
            return
        }
        computerName.value = info.hostname
        greeted = true
        status.value = LinkStatus(LinkState.ONLINE)
        // After a reconnect the daemon has forgotten what this phone was following.
        if (watched.isNotEmpty()) channel.send(PaseoRequests.watch(UUID.randomUUID().toString(), watched))
        channel.send(PaseoRequests.listAgents(LIST_ON_CONNECT))
    }

    private fun happened(event: RemoteEvent) {
        when (event) {
            is RemoteEvent.AgentChanged -> learned(event.agent)
            is RemoteEvent.AgentRemoved -> forget(event.agentId)
            else -> Unit
        }
        events.tryEmit(event)
    }

    private companion object {
        const val BUFFERED = 256

        /** The list fetched on every connect is not anyone's request; its answer fills [agents] by itself. */
        const val LIST_ON_CONNECT = "list-on-connect"
    }
}

/**
 * The agents on a computer that runs a Paseo daemon, over a [SecureChannel]. Nothing of Paseo's
 * frame format leaves this class: callers get the app's own types.
 */
class PaseoSession(
    private val channel: SecureChannel,
    identity: ClientIdentity,
    expectedPeer: String,
    scope: CoroutineScope,
) : AgentSession {
    private val inbox = PaseoInbox(channel, identity, expectedPeer)

    /** The raw conversation for the feature clients (terminals, files, workspaces…). */
    val rpc: PaseoRpc = PaseoLink(inbox, channel)
    override val status: StateFlow<LinkStatus> = inbox.status
    override val computerName: StateFlow<String> = inbox.computerName
    override val agents: StateFlow<List<RemoteAgent>> = inbox.agents
    override val events: SharedFlow<RemoteEvent> = inbox.events
    override val catalog: AgentCatalog = PaseoCatalog(inbox::ask)

    init {
        scope.launch { channel.incoming.collect(inbox::take) }
        scope.launch { channel.status.collect(inbox::linkChanged) }
    }

    private val ask = inbox::ask

    override suspend fun alive(): Boolean {
        val asked = withTimeoutOrNull(BEAT_TIMEOUT_MS) { ask { PaseoPing.request(it, System.currentTimeMillis()) } }
        val answered = asked?.isSuccess == true
        // Greeted and silent: the connection is dead although nobody said so. Not greeted: it is being made already.
        if (!answered && inbox.greeted) channel.restart()
        return answered
    }

    override suspend fun refresh(): Result<List<RemoteAgent>> =
        ask(PaseoRequests::listAgents).map { reply ->
            reply.agents.filterNot { it.archived }.also { inbox.agents.value = it }
        }

    override suspend fun create(spec: NewAgent): Result<RemoteAgent> =
        ask { PaseoRequests.createAgent(it, spec) }.mapCatching { reply ->
            checkNotNull(reply.agent) { NO_ANSWER }.also(inbox::learned)
        }

    override suspend fun history(agentId: String): Result<List<RemoteItem>> {
        val page = ask { PaseoRequests.timeline(it, agentId) }
        return page.map { it.items }
    }

    override suspend fun watch(agentIds: List<String>): Result<Unit> {
        inbox.watched = agentIds
        return ask { PaseoRequests.watch(it, agentIds) }.map { }
    }

    override suspend fun send(
        agentId: String,
        text: String,
        images: List<File>,
    ): Result<Unit> {
        val messageId = UUID.randomUUID().toString()
        if (images.isEmpty()) return ask { PaseoRequests.send(it, agentId, text, messageId) }.map { }
        val pictures = images.map { Attachment(Attachment.IMAGE, it.name, it.absolutePath, PICTURE_MIME) }
        return PaseoAttachments(rpc).send(agentId, text, pictures, messageId)
    }

    override suspend fun cancel(agentId: String): Result<Unit> = ask { PaseoRequests.cancel(it, agentId) }.map { }

    override suspend fun archive(agentId: String): Result<Unit> {
        val done = ask { PaseoRequests.archive(it, agentId) }
        return done.map { inbox.forget(agentId) }
    }

    override fun answer(
        agentId: String,
        permissionId: String,
        reply: PermissionReply,
    ): Boolean = inbox.greeted && channel.send(PaseoRequests.answer(agentId, permissionId, reply))

    override fun close() = channel.close()

    companion object {
        private const val BEAT_TIMEOUT_MS = 10_000L
        private const val PICTURE_MIME = "image/jpeg"
        const val NOT_CONNECTED = "还没连上这台电脑"
        const val NO_ANSWER = "电脑没有回应这个请求"
        const val ANOTHER_COMPUTER = "这个地址上现在是另一台电脑，不是当初绑定的那台。"
    }
}

/** Paseo as one of the programs a computer can run for this app. */
class PaseoBackend(
    private val http: OkHttpClient,
    private val identity: ClientIdentity,
    private val vpnActive: () -> Boolean,
    private val hooks: ChannelHooks,
) : RemoteBackend {
    override val id: String = PaseoPairing.BACKEND
    override val pairing: Pairing = PaseoPairing

    override fun connect(
        computer: PairedComputer,
        scope: CoroutineScope,
    ): AgentSession {
        val channel = PaseoChannel(computer, scope, http, vpnActive, hooks)
        return PaseoSession(channel, identity, computer.peerId, scope)
    }
}

private const val TAG = "MCP:Paseo"

/** One frame from the computer; one that cannot be read is dropped and logged, never ends the reading. */
internal fun PaseoInbox.take(text: String) {
    val frame = runCatching { PaseoFrames.parse(text) }
    frame.onSuccess(::read).onFailure {
        Log.w(TAG, "dropped a frame of ${text.length} chars: ${it.javaClass.simpleName}")
    }
}
