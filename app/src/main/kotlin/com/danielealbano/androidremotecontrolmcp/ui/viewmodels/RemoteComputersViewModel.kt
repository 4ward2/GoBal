package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterCard
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.remote.AgentSession
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkState
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkStatus
import com.danielealbano.androidremotecontrolmcp.services.remote.NewAgent
import com.danielealbano.androidremotecontrolmcp.services.remote.PairedComputer
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteAgent
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteContact
import com.danielealbano.androidremotecontrolmcp.services.remote.RoutePolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import javax.inject.Inject

/** A bound computer as the page shows it: never more of the credential than its fingerprint. */
data class ComputerRow(
    val id: String,
    val name: String,
    val fingerprint: String,
    /** "中继" or the address of a direct route; the relay's own address is not a secret. */
    val route: String,
    val relayOverride: String = "",
    val link: LinkStatus = LinkStatus(LinkState.CONNECTING),
    val agents: List<RemoteAgent> = emptyList(),
)

data class RemoteComputersUiState(
    val computers: List<ComputerRow> = emptyList(),
    /** True while some VPN app carries the phone's traffic: connections go through it. */
    val vpnActive: Boolean = false,
    val busy: Boolean = false,
    val notice: String? = null,
)

/** What the user typed to reach a computer by its address. */
data class ManualAddress(
    val host: String,
    val port: String,
    val tls: Boolean,
    val password: String,
) {
    /** The address as the pairing reader takes it. */
    fun link(): String {
        val secret = password.takeIf { it.isNotEmpty() }?.let { "password=" + URLEncoder.encode(it, "UTF-8") }
        val query = listOfNotNull("ssl=true".takeIf { tls }, secret)
        val suffix = if (query.isEmpty()) "" else "?" + query.joinToString("&")
        return "tcp://${host.trim()}:${port.trim()}$suffix"
    }
}

private const val RELAY = "中继"
private const val UNNAMED = "电脑"
private const val NAME_CHARS = 24

/** The row for [computer]; what is already known about its link and agents stays on screen. */
private fun rowOf(
    computer: PairedComputer,
    previous: ComputerRow?,
): ComputerRow {
    val first = computer.routes.firstOrNull()
    val route = if (computer.peerKey.isNotEmpty()) RELAY else first?.endpoint.orEmpty()
    val name = computer.name.ifBlank { previous?.name.orEmpty() }.ifBlank { UNNAMED }
    val row = ComputerRow(computer.id, name, computer.fingerprint, route, computer.relayOverride)
    return if (previous == null) row else row.copy(link = previous.link, agents = previous.agents)
}

/** The rows with what was just heard from the computer [id]. */
private fun List<ComputerRow>.heard(
    id: String,
    link: LinkStatus,
    agents: List<RemoteAgent>,
): List<ComputerRow> = map { if (it.id == id) it.copy(link = link, agents = agents.sortedBy { a -> a.title }) else it }

/**
 * Makes agents on computers into contacts: a character card whose "model" is the agent, so it
 * shows up like any other.
 */
class AgentContacts(
    private val characters: CharacterRepository,
) {
    /** The contact for [agent], made once; null when it could not be made. */
    suspend fun of(
        computerName: String,
        contact: RemoteContact,
        agent: RemoteAgent,
    ): CharacterCard? {
        characters.load()
        val known = characters.characters.value.firstOrNull { RemoteContact.of(it.model) == contact }
        return known ?: create(computerName, contact, agent)
    }

    private suspend fun create(
        computerName: String,
        contact: RemoteContact,
        agent: RemoteAgent,
    ): CharacterCard? {
        val card =
            buildJsonObject {
                put("name", agent.title.ifBlank { agent.provider }.take(NAME_CHARS))
                put("description", "电脑「$computerName」上的 ${agent.provider} 代理\n${agent.cwd}\n${agent.id}")
            }
        val imported = characters.import(card.toString().toByteArray()).getOrNull()
        return imported?.copy(model = contact.choice())?.also { characters.update(it) }
    }
}

/** The page of paired computers: binding one, its agents, starting a new one, unbinding. */
@HiltViewModel
class RemoteComputersViewModel
    @Inject
    constructor(
        private val computers: RemoteComputers,
        private val characters: CharacterRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _ui = MutableStateFlow(RemoteComputersUiState())
        val ui: StateFlow<RemoteComputersUiState> = _ui.asStateFlow()
        private val _opened = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** Fires when a conversation with an agent has been made the open one. */
        val opened: SharedFlow<Unit> = _opened.asSharedFlow()
        private val watching = mutableMapOf<String, Job>()
        private val contacts = AgentContacts(characters)

        init {
            viewModelScope.launch(ioDispatcher) {
                computers.load()
                computers.computers.collect(::show)
            }
        }

        private fun show(bound: List<PairedComputer>) {
            _ui.update { state ->
                val rows = bound.map { c -> rowOf(c, state.computers.firstOrNull { it.id == c.id }) }
                state.copy(computers = rows, vpnActive = computers.vpnActive)
            }
            val gone = watching.keys.filterNot { id -> bound.any { it.id == id } }
            gone.forEach { watching.remove(it)?.cancel() }
            // A session is replaced when its computer's entry changes; one already followed is left alone.
            bound.filter { it.id !in watching }.forEach { c -> follow(c.id)?.let { watching[c.id] = it } }
        }

        private fun follow(id: String): Job? {
            val session = computers.session(id)
            return session?.let {
                viewModelScope.launch(ioDispatcher) {
                    combine(it.status, it.agents, it.computerName, ::Triple).collect { (link, agents, name) ->
                        _ui.update { state ->
                            val rows = state.computers.heard(id, link, agents)
                            state.copy(computers = rows, vpnActive = computers.vpnActive)
                        }
                        // The first time a computer says its name, that becomes what it is called here.
                        if (name.isNotEmpty() && link.state == LinkState.ONLINE) computers.nameIfUnnamed(id, name)
                    }
                }
            }
        }

        /** Binds the computer a scanned or pasted pairing link describes. */
        fun pair(input: String) {
            viewModelScope.launch(ioDispatcher) {
                val read = computers.read(input)
                val computer = read.getOrNull()
                val barred = computer?.routes?.firstNotNullOfOrNull(RoutePolicy::refusal)
                val notice =
                    when {
                        computer == null -> read.exceptionOrNull()?.message ?: NOT_READ
                        barred != null -> barred
                        else -> BOUND.also { computers.add(computer) }
                    }
                _ui.update { it.copy(notice = notice) }
            }
        }

        fun unpair(id: String) {
            viewModelScope.launch(ioDispatcher) { computers.remove(id) }
        }

        /** Uses another relay than the one the pairing named ("" to go back to it). */
        fun setRelay(
            id: String,
            endpoint: String,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val stored = computers.computers.value.firstOrNull { it.id == id }
                if (stored != null) {
                    watching.remove(id)?.cancel()
                    computers.update(stored.copy(relayOverride = endpoint.trim()))
                }
            }
        }

        fun refresh(id: String) {
            viewModelScope.launch(ioDispatcher) {
                computers.session(id)?.refresh()?.onFailure { e -> _ui.update { it.copy(notice = e.message) } }
            }
        }

        /** Starts an agent on the computer and opens the conversation with it. */
        fun startAgent(
            id: String,
            spec: NewAgent,
        ) {
            if (_ui.value.busy) return
            _ui.update { it.copy(busy = true, notice = null) }
            viewModelScope.launch(ioDispatcher) {
                val made = computers.session(id)?.create(spec)
                made?.getOrNull()?.let { talkTo(id, it) }
                _ui.update { it.copy(busy = false, notice = made?.exceptionOrNull()?.message) }
            }
        }

        /** Makes the agent a contact (once) and opens the conversation with it. */
        fun open(
            id: String,
            agent: RemoteAgent,
        ) {
            viewModelScope.launch(ioDispatcher) { talkTo(id, agent) }
        }

        private suspend fun talkTo(
            computerId: String,
            agent: RemoteAgent,
        ) {
            val computer = _ui.value.computers.firstOrNull { it.id == computerId }
            val card = contacts.of(computer?.name.orEmpty(), RemoteContact(computerId, agent.id), agent)
            if (card == null) {
                _ui.update { it.copy(notice = NO_CONTACT) }
            } else {
                characters.setActive(card.id)
                _opened.emit(Unit)
            }
        }

        fun noticeShown() = _ui.update { it.copy(notice = null) }

        private companion object {
            const val BOUND = "已绑定，正在连接…"
            const val NOT_READ = "没读懂这个配对信息"
            const val NO_CONTACT = "没能把这个代理加为联系人"
        }
    }
