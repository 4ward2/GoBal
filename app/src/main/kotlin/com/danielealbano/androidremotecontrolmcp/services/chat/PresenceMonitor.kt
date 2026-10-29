package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** What the presence dot under a contact's avatar says about its model API. */
enum class Presence(
    val label: String,
) {
    /** Not probed yet. */
    CHECKING("连接中"),
    ONLINE("在线"),
    INVISIBLE("隐身"),
    BUSY("勿扰"),
    BLOCKED("拉黑"),
    ;

    val reachable: Boolean get() = this == ONLINE
}

/**
 * Liveness of every model API, keyed by provider id ([ON_DEVICE] for the phone's own
 * `provider.json` path). Probed once when the app starts; afterwards each send keeps it current:
 * a reply puts the API back online, an error puts the contact into a randomly picked
 * away state (隐身 / 勿扰 / 拉黑), the way a messenger friend who stops answering would look.
 */
@Singleton
class PresenceMonitor
    @Inject
    constructor(
        private val gateway: ModelGateway,
        private val loopback: PhoneMcpLoopbackClient,
        private val settings: AssistSettingsRepository,
    ) {
        private val _state = MutableStateFlow<Map<String, Presence>>(emptyMap())
        val state: StateFlow<Map<String, Presence>> = _state.asStateFlow()

        private val checked = AtomicBoolean(false)

        /** Away states are drawn from here; tests replace it to make the pick deterministic. */
        var pickAway: () -> Presence = { AWAY_STATES.random() }

        fun presenceOf(choice: ModelChoice): Presence = _state.value[keyOf(choice)] ?: Presence.CHECKING

        /** Probes every configured API; runs once per process, later calls return at once. */
        suspend fun checkAllOnce() {
            if (!checked.compareAndSet(false, true)) return
            checkAll()
        }

        suspend fun checkAll() {
            val providers = settings.providers.first()
            val status = runCatching { loopback.status() }.getOrNull()
            val onDevice = status != null && status.httpOk && status.provider.configured
            val results = mutableMapOf(ON_DEVICE to onDevice)
            providers.forEach { provider -> results[provider.id] = gateway.probe(provider) }
            _state.update { current ->
                results.mapValues { (key, alive) -> if (alive) Presence.ONLINE else awayFor(current[key]) }
            }
        }

        /** A reply came back: whoever answered is online again. */
        fun markAlive(choice: ModelChoice) {
            _state.update { it + (keyOf(choice) to Presence.ONLINE) }
        }

        /** The API failed: the contact goes away, keeping the same away state until it is back. */
        fun markFailed(choice: ModelChoice) {
            val key = keyOf(choice)
            _state.update { it + (key to awayFor(it[key])) }
        }

        private fun awayFor(current: Presence?): Presence = current?.takeIf { it in AWAY_STATES } ?: pickAway()

        private fun keyOf(choice: ModelChoice): String = if (choice.onDevice) ON_DEVICE else choice.providerId

        companion object {
            const val ON_DEVICE = ""
            val AWAY_STATES = listOf(Presence.INVISIBLE, Presence.BUSY, Presence.BLOCKED)
        }
    }
