package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.group.Group
import com.danielealbano.androidremotecontrolmcp.services.group.GroupCandidate
import com.danielealbano.androidremotecontrolmcp.services.group.GroupChats
import com.danielealbano.androidremotecontrolmcp.services.group.GroupMember
import com.danielealbano.androidremotecontrolmcp.services.group.GroupMessage
import com.danielealbano.androidremotecontrolmcp.services.group.GroupRoster
import com.danielealbano.androidremotecontrolmcp.services.group.SubModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One candidate on the new-group page, with what the user chose for it. */
data class CandidateRow(
    val candidate: GroupCandidate,
    val included: Boolean = false,
    val admin: Boolean = false,
    val models: List<SubModel> = emptyList(),
)

/** The new-group page. */
data class GroupDraft(
    val scanning: Boolean = true,
    val name: String = "",
    val rows: List<CandidateRow> = emptyList(),
) {
    val chosen: List<CandidateRow> get() = rows.filter { it.included }

    /** A group needs someone to hand out the work and at least one more to do it; each model of a member counts. */
    val ready: Boolean get() = chosen.sumOf { it.models.size.coerceAtLeast(1) } >= 2 && chosen.any { it.admin }
}

/** The group on screen. */
data class GroupView(
    val group: Group? = null,
    val messages: List<GroupMessage> = emptyList(),
    val working: Boolean = false,
    /** Another group's members are at work: this one has to wait. */
    val busyElsewhere: Boolean = false,
)

/** What the user changes on the new-group page: the name, who is in, who is an admin, which models. */
class GroupDrafting(
    private val draft: MutableStateFlow<GroupDraft?>,
) {
    fun rename(name: String) = draft.update { it?.copy(name = name) }

    fun toggle(id: String) = row(id) { it.copy(included = !it.included, admin = it.admin && it.included.not()) }

    /** An admin is a member too. */
    fun toggleAdmin(id: String) = row(id) { it.copy(admin = !it.admin, included = it.included || !it.admin) }

    /** Adds or removes one of the models a member may answer with; the last one stays. */
    fun toggleModel(
        id: String,
        model: SubModel,
    ) = row(id) { row ->
        val next = if (model in row.models) row.models - model else row.models + model
        row.copy(models = next.ifEmpty { row.models })
    }

    private fun row(
        id: String,
        change: (CandidateRow) -> CandidateRow,
    ) = draft.update { current ->
        current?.copy(rows = current.rows.map { if (it.candidate.member.id == id) change(it) else it })
    }
}

/** Group chats: making one from whoever answers right now, and talking in it. */
@HiltViewModel
class GroupViewModel
    @Inject
    constructor(
        private val chats: GroupChats,
        private val roster: GroupRoster,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val groups: StateFlow<List<Group>> = chats.groups
        private val _draft = MutableStateFlow<GroupDraft?>(null)

        /** Not null while the new-group page is up. */
        val draft: StateFlow<GroupDraft?> = _draft.asStateFlow()

        /** The user's choices on the new-group page. */
        val drafting = GroupDrafting(_draft)
        private val openId = MutableStateFlow<String?>(null)

        val view: StateFlow<GroupView> =
            combine(openId, chats.groups, chats.messages, chats.working) { id, groups, messages, working ->
                val group = groups.firstOrNull { it.id == id }
                GroupView(group, messages[id].orEmpty(), working == id && id != null, working != null && working != id)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, GroupView())

        init {
            viewModelScope.launch(ioDispatcher) { chats.load() }
        }

        /** Opens the new-group page and looks for everyone who answers. */
        fun startDraft() {
            _draft.value = GroupDraft()
            viewModelScope.launch(ioDispatcher) {
                val found = runCatching { roster.scan() }.getOrDefault(emptyList())
                _draft.update { it?.copy(scanning = false, rows = found.map(::rowOf)) }
            }
        }

        private fun rowOf(candidate: GroupCandidate) = CandidateRow(candidate, models = candidate.options.take(1))

        fun cancelDraft() {
            _draft.value = null
        }

        /** Makes the group and opens it. */
        fun create() {
            val draft = _draft.value?.takeIf { it.ready } ?: return
            val members = draft.chosen.map { memberOf(it) }
            viewModelScope.launch(ioDispatcher) {
                val group = chats.create(draft.name.trim(), members)
                _draft.value = null
                open(group.id)
            }
        }

        private fun memberOf(row: CandidateRow): GroupMember {
            val member = row.candidate.member
            return member.copy(models = row.models, admin = row.admin)
        }

        fun open(id: String) {
            viewModelScope.launch(ioDispatcher) {
                chats.open(id)
                openId.value = id
            }
        }

        fun close() {
            openId.value = null
        }

        fun say(text: String) {
            val id = openId.value ?: return
            if (text.isNotBlank()) viewModelScope.launch(ioDispatcher) { chats.say(id, text.trim()) }
        }

        fun stop() = chats.stop()

        fun delete(id: String) {
            if (openId.value == id) close()
            viewModelScope.launch(ioDispatcher) { chats.delete(id) }
        }
    }
