package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRun
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.NativeChatUiState

/** "Scroll to this message": [nonce] makes asking for the same message twice a new request. */
data class JumpRequest(
    val id: String,
    val nonce: Int,
)

/** How a row is dressed beyond its content. */
data class RowLook(
    /** Flashing after a jump to it. */
    val highlighted: Boolean = false,
    /** The word being searched for; its occurrences are marked in the text. */
    val search: String = "",
    /** Left out of what the model is sent: drawn faded, with a label. */
    val excluded: Boolean = false,
    /** While several messages are being picked: whether this one is; null outside that mode. */
    val selected: Boolean? = null,
    /** For a task card: the phone task it shows, live while it runs; null when there is no record. */
    val task: AgentRun? = null,
)

/** Which of a task's steps its card lists: the newest few, numbered as they happened. Pure. */
object TaskCardSteps {
    const val SHOWN = 4
    private const val FAILED_MARK = "（没成功）"

    /** How many earlier steps are folded away above the listed ones. */
    fun hidden(run: AgentRun): Int = (run.steps.size - SHOWN).coerceAtLeast(0)

    /** "3. 点「搜索」" for each listed step; a step that failed is marked. */
    fun lines(run: AgentRun): List<String> {
        val from = hidden(run)
        return run.steps.drop(from).mapIndexed { i, step ->
            val mark = if (step.ok) "" else FAILED_MARK
            "${from + i + 1}. ${step.label}$mark"
        }
    }
}

/** What the selection bar offers for the picked messages. */
class SelectionActions(
    val onExclude: () -> Unit,
    val onInclude: () -> Unit,
    val onCancel: () -> Unit,
)

/** What the message list draws, and the screen state it reads and reports to. */
class MessageListState(
    val ui: NativeChatUiState,
    val speaker: Speaker,
    val items: List<ChatItem>,
    val view: ChatViewState,
    val interactions: InteractionState,
)

/** Finding one's way around the conversation: search, the question outline and jumps. */
@Stable
class ChatViewState {
    var searching by mutableStateOf(false)
        private set
    var query by mutableStateOf("")

    /** Which of the search hits is the current one. */
    var hit by mutableStateOf(0)
    var outline by mutableStateOf(false)
    var jump by mutableStateOf<JumpRequest?>(null)
        private set
    private var nonce = 0

    /** The trimmed search word, "" when the search bar is closed. */
    val activeQuery: String get() = if (searching) query.trim() else ""

    fun jumpTo(id: String) {
        nonce += 1
        jump = JumpRequest(id, nonce)
    }

    fun openSearch() {
        searching = true
    }

    fun closeSearch() {
        searching = false
        query = ""
        hit = 0
    }
}

@Composable
fun rememberChatViewState(): ChatViewState = remember { ChatViewState() }
