package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRun
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRunner
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBackend
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBubble
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpChatLogic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A phone task as the conversation records it: its message, and an error notice when it did not start. */
class StartedTask(
    val bubble: ChatBubble,
    val error: String? = null,
)

/** Turning "do this on the phone" into the assistant's message for it. */
object PhoneTasks {
    const val BUSY = "手机上还有一个操作没做完。等它结束，或者先点「停止」。"
    const val NO_HELPER_HINT = "给这个联系人选一个服务商模型（联系人页 → 对话模型）后，就能直接操作手机，不需要 root。"
    const val NO_MODEL = "这个联系人还没选对话模型，手机操作需要一个模型来想每一步。$NO_HELPER_HINT"

    /** The in-app agent took the task ([taskId]) or is busy with another one (null). */
    fun inApp(taskId: String?): StartedTask =
        if (taskId != null) {
            StartedTask(PhoneMcpChatLogic.taskBubble(taskId))
        } else {
            StartedTask(PhoneMcpChatLogic.newBubble(role = "assistant", content = BUSY), BUSY)
        }

    /**
     * Every phone task runs in the app, so its card can show the plan, the progress and the map.
     * With [noModel] (the contact has none chosen) it thinks with the phone's default model, which
     * only a [rooted] phone's helper serves; without that the user is told what to choose instead.
     */
    fun start(
        noModel: Boolean,
        rooted: Boolean,
        agent: () -> String?,
    ): StartedTask =
        if (noModel && !rooted) {
            StartedTask(PhoneMcpChatLogic.newBubble(role = "assistant", content = NO_MODEL), NO_MODEL)
        } else {
            inApp(agent())
        }

    /** What the model should later read in place of a finished task's placeholder message. */
    fun record(run: AgentRun): String = "（手机操作「${run.goal}」：${run.result}）"
}

/**
 * Keeps the chat's task cards current: [NativeChatUiState.tasks] follows the agent's running and
 * finished tasks, and when a task ends its message in the conversation gets the outcome written
 * into it, so the model knows later what happened on the phone.
 */
internal class TaskWatcher(
    private val backend: ChatBackend,
    private val agent: AgentRunner,
    private val ui: MutableStateFlow<NativeChatUiState>,
) {
    fun observe(
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher,
    ) {
        val runs = agent.runs
        scope.launch(ioDispatcher) {
            combine(runs.current, runs.past) { current, past -> (past + listOfNotNull(current)).associateBy { it.id } }
                .collect { tasks -> ui.update { it.copy(tasks = tasks) } }
        }
        scope.launch(ioDispatcher) { runs.current.collect { run -> if (run?.status?.finished == true) write(run) } }
    }

    /** Idempotent: a message that already carries the outcome is left alone. */
    private suspend fun write(run: AgentRun) {
        val record = PhoneTasks.record(run)
        val state = ui.value
        val messages = state.messages
        if (messages.none { it.task == run.id && it.content != record }) return
        val written: (List<ChatBubble>) -> List<ChatBubble> = { all ->
            all.map { if (it.task == run.id) it.copy(content = record) else it }
        }
        backend.keep(state.conversation, written(messages))
        ui.updateAt(state.conversation) { it.copy(messages = written(it.messages)) }
    }
}
