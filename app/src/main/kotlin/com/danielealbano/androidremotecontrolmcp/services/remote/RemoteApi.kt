package com.danielealbano.androidremotecontrolmcp.services.remote

import com.danielealbano.androidremotecontrolmcp.services.link.Reconnects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/*
 * The connection to a computer, in three layers. Each has one implementation today (Paseo's
 * protocol, in the `paseo` package); contacts, the chat screen and notifications use the third
 * layer and the types in this package only, so another program on the computer, or the app's own
 * channel, is a new implementation and nothing above changes.
 *
 *   1. Pairing       a QR code or link  ->  a PairedComputer
 *   2. SecureChannel an end-to-end encrypted two-way message channel to it, with reconnects
 *   3. AgentSession  agents on it: list, start, talk to, watch, approve, stop
 */

/** One way to reach a computer. */
@Serializable
data class Route(
    /** [RELAY] or [DIRECT]. */
    val kind: String,
    /** `host:port`. */
    val endpoint: String,
    val tls: Boolean = false,
) {
    companion object {
        const val RELAY = "relay"
        const val DIRECT = "direct"
    }
}

/**
 * A computer this phone is bound to. This is the credential: whoever holds it can talk to that
 * computer. It is kept encrypted, never logged and never shown; the user sees [name] and
 * [fingerprint] only.
 */
@Serializable
data class PairedComputer(
    /** This phone's own handle for it. */
    val id: String,
    val name: String,
    /** Which program answers on the computer; decides the implementation of all three layers. */
    val backend: String,
    /** The version of that program's pairing and channel format this entry was written for. */
    val protocol: Int,
    /** How the computer names itself; "" until it has said so. */
    val peerId: String = "",
    /** The computer's public key, base64; "" when the route itself is the trust (a private network). */
    val peerKey: String = "",
    val routes: List<Route> = emptyList(),
    /** A password the computer asks for on a direct route; "" when none. */
    val secret: String = "",
    /** `host:port` of another relay to use instead of the one the pairing named; "" for none. */
    val relayOverride: String = "",
    val addedAt: Long = 0,
) {
    /** A few characters that tell two computers apart without revealing the credential. */
    val fingerprint: String
        get() {
            val seed = peerKey.ifEmpty { routes.joinToString("|") { it.endpoint } + "|" + peerId }
            val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            return digest.takeLast(FINGERPRINT_BYTES).joinToString("") { "%02x".format(it) }
        }

    /** The routes to try, in order, with the user's own relay in place of the paired one when set. */
    fun routesToTry(): List<Route> =
        routes.map { route ->
            val swapped = route.kind == Route.RELAY && relayOverride.isNotBlank()
            if (swapped) route.copy(endpoint = relayOverride.trim()) else route
        }

    private companion object {
        const val FINGERPRINT_BYTES = 3
    }
}

/** Layer 1: turns what the user scanned or pasted into a computer to bind. */
interface Pairing {
    /** The computer the text describes; a failure carries a message for the user. */
    fun read(
        input: String,
        now: Long,
    ): Result<PairedComputer>
}

enum class LinkState {
    CONNECTING,
    ONLINE,

    /** The computer cannot be reached right now: it is off, asleep or the network is down. Retried. */
    OFFLINE,

    /** The computer answered and refused this phone: the pairing no longer holds. Not retried. */
    REJECTED,
}

data class LinkStatus(
    val state: LinkState,
    /** Why, in the user's words; "" when online. */
    val reason: String = "",
)

/**
 * Layer 2: an encrypted two-way message channel to one computer. It reconnects by itself and
 * says where it stands; a message handed to [send] while it is not [LinkState.ONLINE] is refused,
 * never queued silently.
 */
interface SecureChannel {
    val status: StateFlow<LinkStatus>

    /** Messages from the computer, decrypted, in order. */
    val incoming: SharedFlow<String>

    /** False when the message could not be handed to the network. */
    fun send(message: String): Boolean

    /** Gives up the connection in use and makes a new one: it was found dead. */
    fun restart() = Unit

    fun close()
}

/** What a channel asks of the app around it: when to try again, and to stay awake for what arrives. */
class ChannelHooks(
    val reconnects: Reconnects,
    val heard: () -> Unit,
)

/**
 * What a computer can start, asked of the computer itself every time. Model names change by the
 * week: a list built into the app would be out of date, and an out-of-date list is worse than none.
 */
interface AgentCatalog {
    suspend fun providers(): Result<List<RemoteProvider>>

    /** The models and permission modes [provider] offers right now. */
    suspend fun choices(provider: String): Result<ProviderChoices>
}

/**
 * Layer 3: the agents on one computer. Every call fails with a message for the user rather than
 * hanging when the computer is not there.
 */
interface AgentSession {
    val status: StateFlow<LinkStatus>

    /** How the computer names itself, once it has. */
    val computerName: StateFlow<String>

    /** The agents on the computer that are not archived, as last heard. */
    val agents: StateFlow<List<RemoteAgent>>

    /** What happens there while connected; only for the agents named in [watch]. */
    val events: SharedFlow<RemoteEvent>

    /** What can be started there. */
    val catalog: AgentCatalog

    /**
     * Asks the computer whether it is still there, end to end. False when nothing came back in
     * time; the connection is then given up and made anew.
     */
    suspend fun alive(): Boolean

    suspend fun refresh(): Result<List<RemoteAgent>>

    suspend fun create(spec: NewAgent): Result<RemoteAgent>

    /** The agent's conversation so far, oldest first, as complete messages. */
    suspend fun history(agentId: String): Result<List<RemoteItem>>

    /** Which agents' conversations to follow live; replaces the previous choice. */
    suspend fun watch(agentIds: List<String>): Result<Unit>

    /** Says [text] to the agent, with the pictures the user attached to it. */
    suspend fun send(
        agentId: String,
        text: String,
        images: List<File> = emptyList(),
    ): Result<Unit>

    /** Stops what the agent is doing. */
    suspend fun cancel(agentId: String): Result<Unit>

    suspend fun archive(agentId: String): Result<Unit>

    /** The user's answer to a permission request. Only ever called from a tap. */
    fun answer(
        agentId: String,
        permissionId: String,
        reply: PermissionReply,
    ): Boolean

    fun close()
}

/** One kind of program a computer can run for the phone to talk to. */
interface RemoteBackend {
    /** Stored in [PairedComputer.backend]. */
    val id: String
    val pairing: Pairing

    fun connect(
        computer: PairedComputer,
        scope: CoroutineScope,
    ): AgentSession
}

const val REQUEST_TIMEOUT_MS = 30_000L
