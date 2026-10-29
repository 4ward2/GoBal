package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.services.chat.ChatApiResult
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBackend
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBubble
import com.danielealbano.androidremotecontrolmcp.services.chat.OutgoingMessages
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpChatLogic
import com.danielealbano.androidremotecontrolmcp.services.skill.Skill
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillChatText
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The model and the saved conversation, for recording and replaying. Hidden notices go to the model
 * as the last user message and are never saved; the agent's answer is.
 */
internal class SkillConversation(
    private val backend: ChatBackend,
    private val repository: SkillRepository,
    private val ui: MutableStateFlow<NativeChatUiState>,
) {
    suspend fun notify(notice: String) = ask(ui.value.messages, notice) { it }

    /** Adds [bubble] to the conversation and saves it. */
    suspend fun append(bubble: ChatBubble) {
        val state = ui.value
        val messages = state.messages + bubble
        backend.keep(state.conversation, messages)
        ui.updateAt(state.conversation) { it.copy(messages = messages) }
    }

    /**
     * Asks the model with [hidden] as a last, unsaved user message; the answer (after [shape]) is
     * added to the conversation like any reply.
     */
    suspend fun ask(
        visible: List<ChatBubble>,
        hidden: String,
        shape: suspend (String) -> String,
    ) {
        val at = ui.value.conversation
        ui.update { it.copy(sending = true, error = null) }
        val prompt = visible + ChatBubble(role = "user", content = hidden)
        val messages = OutgoingMessages.toModelMessages(backend.store.systemPrompt(), prompt)
        val choice = backend.store.modelChoice()
        val result =
            runCatching { backend.gateway.chat(messages, choice) }
                .getOrElse { ChatApiResult(ok = false, error = it.message.orEmpty()) }
        if (!result.ok) {
            backend.presence.markFailed(choice)
            val error = PhoneMcpChatLogic.formatChatError(result.error.ifBlank { "empty" })
            ui.updateAt(at) { it.copy(sending = false, error = error) }
            return
        }
        backend.presence.markAlive(choice)
        val text = shape(backend.store.receive(result.content))
        // The answer belongs to the conversation that was asked, even if another one is open by now.
        val now = ui.value
        val asked = if (now.conversation == at || at == null) now.messages else backend.store.loadAt(at)
        val next = asked + PhoneMcpChatLogic.newBubble(role = "assistant", content = text)
        backend.keep(at, next)
        ui.updateAt(at) { it.copy(messages = next, sending = false) }
    }

    /** Saves [changed] in place of the skill named [oldName] and rewrites its card. */
    suspend fun apply(
        oldName: String,
        changed: Skill,
    ) {
        val saved =
            repository.save(changed, replace = true, previousName = oldName).getOrElse { e ->
                ui.update { it.copy(error = "没改成：${e.message}") }
                return
            }
        val state = ui.value
        val messages =
            state.messages.map { b ->
                if (b.skill == oldName) b.copy(skill = saved.name, content = SkillChatText.card(saved)) else b
            }
        backend.keep(state.conversation, messages)
        ui.updateAt(state.conversation) { s ->
            val reviewing = if (s.skill.reviewing == oldName) saved.name else s.skill.reviewing
            s.copy(messages = messages, skill = s.skill.copy(reviewing = reviewing))
        }
    }
}
