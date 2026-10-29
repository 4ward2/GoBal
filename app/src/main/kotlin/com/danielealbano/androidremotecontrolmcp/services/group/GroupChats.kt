package com.danielealbano.androidremotecontrolmcp.services.group

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.agent.ModelTrouble
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatMessage
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelGateway
import com.danielealbano.androidremotecontrolmcp.services.chat.PresenceMonitor
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModels
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkState
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteChats
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteComputers
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteContact
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Someone who could join a group: found alive just now. */
data class GroupCandidate(
    val member: GroupMember,
    /** The models it could answer with (the member's own first); empty for an agent on a computer. */
    val options: List<SubModel>,
    /** Where it lives, for the list: "手机" or the computer's name. */
    val where: String,
)

/**
 * Finds everyone who answers right now: the assistant and the characters on the phone (through
 * the providers whose services just answered a probe), and the agents on the paired computers
 * whose link is up. Whoever does not answer is not offered.
 */
@Singleton
class GroupRoster
    @Inject
    constructor(
        private val characters: CharacterRepository,
        private val settings: AssistSettingsRepository,
        private val presence: PresenceMonitor,
        private val computers: RemoteComputers,
    ) {
        suspend fun scan(): List<GroupCandidate> = onPhone() + onComputers()

        private suspend fun onPhone(): List<GroupCandidate> {
            presence.checkAll()
            val alive = settings.providers.first().filter { !LocalModels.isLocal(it) && it.models.isNotEmpty() }
            val options =
                alive
                    .filter { presence.presenceOf(ModelChoice(it.id, it.models.first())).reachable }
                    .flatMap { provider -> provider.models.map { SubModel(provider.id, it) } }
            characters.load()
            val name = settings.assistantName.first()
            val own = settings.assistantModel.first()
            val assistant = GroupMember(ASSISTANT, name, persona = "你是用户手机上的助手。")
            val cards = characters.characters.value.filter { RemoteContact.of(it.model) == null }
            val people =
                listOf(assistant to own) +
                    cards.map { card ->
                        val said = listOf(card.description, card.personality).filter { it.isNotBlank() }
                        val who = said.joinToString(" ")
                        GroupMember("char:" + card.id, card.name, persona = who.take(PERSONA_CHARS)) to card.model
                    }
            // Offered only while at least one model answers; its own model comes first in the list.
            return people.filter { options.isNotEmpty() }.map { (member, model) ->
                val first = options.filter { it.providerId == model.providerId && it.model == model.model }
                GroupCandidate(member, first + (options - first.toSet()), PHONE)
            }
        }

        private suspend fun onComputers(): List<GroupCandidate> {
            computers.load()
            return computers.computers.value.flatMap { computer ->
                val session = computers.session(computer.id)
                val status = session?.status
                val settled = withTimeoutOrNull(LINK_WAIT_MS) { status?.first { it.state != LinkState.CONNECTING } }
                val agents = if (settled?.state == LinkState.ONLINE) session?.agents?.value.orEmpty() else emptyList()
                agents.map { agent ->
                    val title = agent.title.ifBlank { agent.provider }.ifBlank { AGENT }
                    val member = GroupMember("remote:${computer.id}/${agent.id}", title, remote = true)
                    GroupCandidate(member, emptyList(), computer.name.ifBlank { COMPUTER })
                }
            }
        }

        companion object {
            const val ASSISTANT = "assistant"
            private const val PHONE = "手机"
            private const val COMPUTER = "电脑"
            private const val AGENT = "代理"
            private const val PERSONA_CHARS = 400
            private const val LINK_WAIT_MS = 6_000L
        }
    }

/**
 * The group chats: who is in them, what was said, and the work in progress. A group's work runs
 * in the app's own scope, so leaving the screen does not cut it short.
 */
@Singleton
class GroupChats
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val gateway: ModelGateway,
        private val remote: RemoteChats,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        private val store = GroupStore(File(context.filesDir, "groups"))
        private val _groups = MutableStateFlow<List<Group>>(emptyList())
        val groups: StateFlow<List<Group>> = _groups.asStateFlow()
        private val _messages = MutableStateFlow<Map<String, List<GroupMessage>>>(emptyMap())

        /** Each group's conversation, by group id; a group appears once it has been opened. */
        val messages: StateFlow<Map<String, List<GroupMessage>>> = _messages.asStateFlow()
        private val _working = MutableStateFlow<String?>(null)

        /** The id of the group whose members are at work, or null. One group works at a time. */
        val working: StateFlow<String?> = _working.asStateFlow()
        private var job: Job? = null

        private val speaker =
            GroupSpeaker { worker, system, text ->
                val member = worker.member
                val reply =
                    if (member.remote) {
                        val (computer, agent) = member.id.removePrefix("remote:").split('/', limit = 2)
                        // An agent on a computer has a system prompt of its own: its brief goes in the message.
                        remote.talk(RemoteContact(computer, agent).choice(), system + "\n\n" + text) {}
                    } else {
                        val model = worker.model ?: SubModel("", "")
                        val asked = listOf(ChatMessage("system", system), ChatMessage("user", text))
                        gateway.chat(asked, ModelChoice(model.providerId, model.model))
                    }
                // A service's own error text can quote part of the key: only its plain reason is kept.
                val why = if (member.remote) reply.error else ModelTrouble.plain(reply.error)
                if (reply.ok) Result.success(reply.content) else Result.failure(IllegalStateException(why))
            }

        fun load() {
            _groups.value = store.groups()
        }

        fun open(id: String) = _messages.update { it + (id to store.messages(id)) }

        fun create(
            name: String,
            members: List<GroupMember>,
        ): Group {
            val now = System.currentTimeMillis()
            val group = Group("grp$now", name.ifBlank { DEFAULT_NAME }, members, now)
            store.save(group)
            load()
            return group
        }

        fun delete(id: String) {
            if (_working.value == id) stop()
            store.delete(id)
            load()
        }

        /** The user says [text] in the group [id]; its admins take it from there. */
        @Synchronized
        fun say(
            id: String,
            text: String,
        ) {
            val group = store.groups().firstOrNull { it.id == id }
            if (group == null || _working.value != null) return
            val now = System.currentTimeMillis()
            took(id, GroupMessage("u$now", GroupProtocol.USER, text, now))
            _working.value = id
            job =
                scope.launch {
                    try {
                        GroupEngine(speaker, System::currentTimeMillis) { took(id, it) }.run(group, store.messages(id))
                    } finally {
                        _working.value = null
                    }
                }
        }

        fun stop() {
            job?.cancel()
        }

        private fun took(
            id: String,
            message: GroupMessage,
        ) {
            store.append(id, message)
            _messages.update { it + (id to store.messages(id)) }
        }

        private companion object {
            const val DEFAULT_NAME = "群聊"
        }
    }
