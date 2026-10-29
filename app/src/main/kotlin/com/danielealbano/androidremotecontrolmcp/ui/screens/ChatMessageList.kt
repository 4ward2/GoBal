@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.chat.BottomTracker
import com.danielealbano.androidremotecontrolmcp.ui.chat.BubbleActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatItem
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatItemRow
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatPresentation
import com.danielealbano.androidremotecontrolmcp.ui.chat.EmptyConversation
import com.danielealbano.androidremotecontrolmcp.ui.chat.JumpToBottomButton
import com.danielealbano.androidremotecontrolmcp.ui.chat.MessageListState
import com.danielealbano.androidremotecontrolmcp.ui.chat.RowLook
import com.danielealbano.androidremotecontrolmcp.ui.chat.SkillReviewCard
import com.danielealbano.androidremotecontrolmcp.ui.chat.Speaker
import com.danielealbano.androidremotecontrolmcp.ui.chat.TypingIndicator
import com.danielealbano.androidremotecontrolmcp.ui.chat.bubbleActions
import com.danielealbano.androidremotecontrolmcp.ui.ideas.IdeaCatalog
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MessageAction
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.NativeChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
internal fun MessageList(
    state: MessageListState,
    viewModel: NativeChatViewModel,
    modifier: Modifier = Modifier,
) {
    val ui = state.ui
    val scope = rememberCoroutineScope()
    // Created at the end of the history, so even the very first frame shows the latest messages.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.items.size - 1).coerceAtLeast(0))
    // The reply in progress (typing dots, then its text) is one more row after the stored messages.
    val lastIndex = state.items.size + (if (ui.sending) 1 else 0) - 1
    val lastKey = if (ui.sending) REPLY_KEY else state.items.lastOrNull()?.key
    HighlightTimeout(ui.highlightId, viewModel)
    JumpOnRequest(state, listState, viewModel)
    KeepAtBottom(listState, lastIndex, lastKey)
    FollowReply(listState, ui.streaming, lastIndex)
    val far by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            BottomTracker.isFar(layout.visibleItemsInfo.lastOrNull()?.index, layout.totalItemsCount - 1)
        }
    }
    // Something arrived while the reader was up in the history: the button says so until they return.
    var unseen by remember { mutableStateOf(false) }
    LaunchedEffect(lastKey) { if (far) unseen = true }
    LaunchedEffect(far) { if (!far) unseen = false }
    Box(modifier.fillMaxWidth()) {
        ConversationRows(state, viewModel, listState)
        JumpToBottomButton(
            visible = far,
            unseen = unseen,
            onClick = { scope.launch { listState.animateScrollToItem(lastIndex.coerceAtLeast(0)) } },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        )
    }
}

/** A message flashes for a moment after a jump to it, then settles. */
@Composable
private fun HighlightTimeout(
    highlightId: String?,
    viewModel: NativeChatViewModel,
) {
    LaunchedEffect(highlightId) {
        if (highlightId != null) {
            delay(HIGHLIGHT_MS)
            viewModel.act(MessageAction.Highlight(null))
        }
    }
}

/** Quotes, search hits and the question outline all ask for "that message": scroll to it and flash it. */
@Composable
private fun JumpOnRequest(
    state: MessageListState,
    listState: LazyListState,
    viewModel: NativeChatViewModel,
) {
    val request = state.view.jump
    LaunchedEffect(request) {
        val id = request?.id
        val index = state.items.indexOfFirst { it.messageId == id }
        if (id != null && index >= 0) {
            viewModel.act(MessageAction.Highlight(id))
            listState.animateScrollToItem(index)
        }
    }
}

@Composable
private fun ConversationRows(
    state: MessageListState,
    viewModel: NativeChatViewModel,
    listState: LazyListState,
) {
    val context = LocalContext.current
    val ui = state.ui
    val suggestions =
        remember {
            IdeaCatalog.quickStarts({ pkg -> context.packageManager.getLaunchIntentForPackage(pkg) != null })
        }
    val actions =
        remember(state.interactions, state.view, viewModel) {
            bubbleActions(state.interactions, context, viewModel, state.view::jumpTo)
        }
    val cardActions = remember(viewModel) { skillCardActions(viewModel) }
    val excluded = remember(ui.messages) { ui.messages.filter { it.excluded }.mapTo(HashSet()) { it.id } }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.items.isEmpty() && !ui.sending) {
            item(key = "empty") {
                // An agent on a computer does not operate this phone: no phone starters, other words.
                val onComputer = ui.remote != null
                val words = if (onComputer) R.string.chat_greeting_remote else R.string.chat_greeting_body
                EmptyConversation(
                    speaker = state.speaker,
                    greeting = ChatPresentation.greeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)),
                    suggestions = if (onComputer) emptyList() else suggestions,
                    onPick = viewModel::setDraft,
                    body = stringResource(words),
                )
            }
        }
        // contentType lets the list reuse a built bubble / task card for the next item of the same
        // kind instead of composing one from scratch as it scrolls in: that composition was the
        // only stage over the 90 Hz frame budget.
        items(state.items, key = { it.key }, contentType = { it::class }) { item: ChatItem ->
            val skill = (item as? ChatItem.SkillReview)?.let { ui.skill.skills[it.skillName] }
            if (skill != null) {
                val active = ui.skill.reviewing == skill.name
                SkillReviewCard(skill, state.speaker, active, ui.skill.focus, cardActions)
            } else {
                ChatItemRow(item, state.speaker, actions, lookOf(item, state, excluded))
            }
        }
        if (ui.sending) item(key = REPLY_KEY) { ReplyInProgress(ui.streaming, state.speaker) }
    }
}

private fun lookOf(
    item: ChatItem,
    state: MessageListState,
    excluded: Set<String>,
): RowLook =
    RowLook(
        highlighted = item.messageId == state.ui.highlightId,
        search = state.view.activeQuery,
        excluded = item.messageId in excluded,
        selected = state.interactions.selected?.contains(item.messageId),
        task = (item as? ChatItem.PhoneTask)?.let { state.ui.tasks[it.taskId] },
    )

/** Typing dots until the first words arrive, then the reply as far as it has been written. */
@Composable
private fun ReplyInProgress(
    streaming: String?,
    speaker: Speaker,
) {
    if (streaming.isNullOrEmpty()) {
        TypingIndicator(speaker)
    } else {
        ChatItemRow(ChatItem.AssistantMessage(REPLY_KEY, streaming), speaker, BubbleActions.None)
    }
}

/**
 * The pane is rebuilt on every screen switch, so the list would otherwise open at the top and
 * then animate down through the whole history (visible flicker). Open at the bottom without
 * animation; animate only for new messages, and never pull the user back while they read history.
 * [lastKey]: the reply takes the typing indicator's place at the same index, and must still be shown.
 */
@Composable
private fun KeepAtBottom(
    listState: LazyListState,
    lastIndex: Int,
    lastKey: Any?,
) {
    var settledCount by remember { mutableStateOf(-1) }
    // True from the moment a scroll to the newest message starts until it has arrived.
    var owed by remember { mutableStateOf(false) }
    val target by rememberUpdatedState(lastIndex)
    LaunchedEffect(lastIndex, lastKey) {
        if (lastIndex < 0) return@LaunchedEffect
        val firstShow = settledCount < 0
        val lastVisible =
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index
        val nearBottom = lastVisible == null || lastVisible >= settledCount - 1
        settledCount = lastIndex + 1
        if (firstShow) {
            listState.scrollToItem(lastIndex)
        } else if (nearBottom) {
            owed = true
            listState.animateScrollToItem(lastIndex)
            owed = false
        }
    }
    // A phone task sends the app to the background mid-animation, and the animation never ends:
    // coming back, go straight to where it was heading.
    LifecycleResumeEffect(listState) {
        if (owed && target >= 0) {
            listState.requestScrollToItem(target)
            owed = false
        }
        onPauseOrDispose { }
    }
}

/**
 * A reply grows inside one row, which [KeepAtBottom] cannot see. While the reader stays at the
 * bottom the list follows the growing text; once they scroll up to read, it lets them be until
 * they come back down themselves.
 */
@Composable
private fun FollowReply(
    listState: LazyListState,
    streaming: String?,
    lastIndex: Int,
) {
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        // Judged whenever a scroll comes to rest: at the very bottom means "keep me there".
        snapshotFlow { listState.isScrollInProgress }.collect { moving ->
            if (!moving) following = !listState.canScrollForward
        }
    }
    LaunchedEffect(streaming?.length) {
        if (following && !streaming.isNullOrEmpty() && lastIndex >= 0) listState.scrollToItem(lastIndex, END_OF_ROW)
    }
}

private const val HIGHLIGHT_MS = 1400L
private const val REPLY_KEY = "typing"

/** A scroll offset past the end of any row: the list clamps it to the row's bottom edge. */
private const val END_OF_ROW = 1_000_000
