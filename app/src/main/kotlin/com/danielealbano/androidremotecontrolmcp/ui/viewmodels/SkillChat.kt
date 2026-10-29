package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBackend
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBubble
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpChatLogic
import com.danielealbano.androidremotecontrolmcp.services.skill.ManualRecording
import com.danielealbano.androidremotecontrolmcp.services.skill.RecordMode
import com.danielealbano.androidremotecontrolmcp.services.skill.RecordingSession
import com.danielealbano.androidremotecontrolmcp.services.skill.Skill
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillChatText
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillCoordinator
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Recording and replaying as the chat shows them. */
data class SkillChatState(
    /** A recording in progress: the composer glows. */
    val recording: RecordMode = RecordMode.NONE,
    /** The recorded skill being reviewed with the agent; messages carry its JSON as hidden context. */
    val reviewing: String? = null,
    /** The step the next message is about (tapped on the card), 0-based. */
    val focus: Int? = null,
    /** Saved skills by name: the review cards draw from these, the play list lists them. */
    val skills: Map<String, Skill> = emptyMap(),
    /** The skill being replayed. */
    val playing: String? = null,
)

/** What [SkillChat] needs besides the chat backend, bundled so the view model stays small. */
@Singleton
class SkillChatDeps
    @Inject
    constructor(
        val recording: RecordingSession,
        val manual: ManualRecording,
        val repository: SkillRepository,
        val coordinator: SkillCoordinator,
    )

/**
 * The chat's side of recording and replaying: starts a recording and tells the agent without a
 * visible message; turns a finished recording into a card the user reorders and discusses; applies
 * the skill changes the agent proposes; replays a skill and has the agent report how it went.
 */
class SkillChat(
    backend: ChatBackend,
    private val deps: SkillChatDeps,
    private val ui: MutableStateFlow<NativeChatUiState>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val talk = SkillConversation(backend, deps.repository, ui)

    fun observe() {
        scope.launch(io) { deps.recording.mode.collect { m -> state { it.copy(recording = m) } } }
        scope.launch(io) {
            deps.repository.load()
            deps.repository.skills.collect { list -> state { it.copy(skills = list.associateBy(Skill::name)) } }
        }
        scope.launch(io) { deps.coordinator.running.collect { r -> state { it.copy(playing = r) } } }
    }

    /**
     * Starts recording and tells the agent. Recording by hand watches the screen through the
     * accessibility service; without it ([canWatchScreen] false) nothing starts and the chat says why.
     */
    fun startRecording(
        ai: Boolean,
        canWatchScreen: Boolean,
    ): Boolean {
        if (!ai && !canWatchScreen) {
            ui.update { it.copy(error = "人工录制需要先在系统设置里打开本应用的无障碍服务") }
            return false
        }
        if (ai) deps.recording.begin(RecordMode.AI) else deps.manual.start()
        val notice = if (ai) SkillChatText.AI_RECORDING_STARTED else SkillChatText.MANUAL_RECORDING_STARTED
        scope.launch(io) { talk.notify(notice) }
        return true
    }

    fun stopRecording() {
        when (deps.recording.mode.value) {
            RecordMode.AI -> deps.recording.end()
            RecordMode.MANUAL -> scope.launch(io) { if (deps.manual.stop()) checkReview() }
            RecordMode.NONE -> Unit
        }
    }

    /** A manual recording finished while the chat was away: show its card and ask the agent to go through it. */
    fun checkReview() {
        val name = deps.recording.takeReview() ?: return
        scope.launch(io) {
            deps.repository.load()
            val skill = deps.repository.find(name) ?: return@launch
            val card =
                PhoneMcpChatLogic
                    .newBubble(role = "assistant", content = SkillChatText.card(skill))
                    .copy(skill = skill.name)
            talk.append(card)
            state { it.copy(reviewing = skill.name, focus = null) }
            talk.notify(SkillChatText.recorded(skill))
        }
    }

    /** Taps on a step: select it for the next message, or let go of it when tapped again. */
    fun focus(
        skillName: String,
        index: Int,
    ) {
        state {
            val same = it.reviewing == skillName && it.focus == index
            it.copy(reviewing = skillName, focus = if (same) null else index)
        }
    }

    /** Reviews [skillName] (null: none) with no step selected. */
    fun review(skillName: String?) = state { it.copy(reviewing = skillName, focus = null) }

    fun reorder(
        skillName: String,
        from: Int,
        to: Int,
    ) {
        scope.launch(io) {
            val skill = deps.repository.find(skillName) ?: return@launch
            talk.apply(skillName, SkillChatText.move(skill, from, to))
        }
    }

    /** The user's message during a review: answered with the skill as hidden context; proposed changes are applied. */
    suspend fun replyInReview(pending: List<ChatBubble>) {
        val name = ui.value.skill.reviewing ?: return
        val skill = deps.repository.find(name) ?: return
        val context = SkillChatText.review(skill, ui.value.skill.focus)
        state { it.copy(focus = null) }
        talk.ask(pending, context) { reply ->
            val edit = SkillChatText.parse(reply, skill)
            edit.skill?.let { talk.apply(name, it) }
            edit.text
        }
    }

    fun play(
        name: String,
        params: Map<String, String>,
    ) {
        scope.launch(io) {
            val result = deps.coordinator.run(name, params)
            talk.notify(SkillChatText.played(result))
        }
    }

    private fun state(change: (SkillChatState) -> SkillChatState) = ui.update { it.copy(skill = change(it.skill)) }
}
