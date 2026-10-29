@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.AppTier
import com.danielealbano.androidremotecontrolmcp.services.chat.Attachment
import com.danielealbano.androidremotecontrolmcp.services.chat.ContextBudget
import com.danielealbano.androidremotecontrolmcp.services.chat.ContextLevel
import com.danielealbano.androidremotecontrolmcp.services.chat.QuotedMessage
import com.danielealbano.androidremotecontrolmcp.services.skill.RecordMode
import com.danielealbano.androidremotecontrolmcp.ui.LocalTier
import com.danielealbano.androidremotecontrolmcp.ui.theme.ContextWarn

/** Everything the composer displays. */
data class ComposerState(
    val draft: String,
    val canSend: Boolean,
    val sending: Boolean,
    /** A reply is being written and can be stopped: the send button becomes a stop button. */
    val stoppable: Boolean = false,
    val listening: Boolean,
    val cancelHint: Boolean,
    val voiceMode: Boolean,
    val replyTo: QuotedMessage? = null,
    val attachment: Attachment? = null,
    val attaching: Boolean = false,
    /** Share of the model's context the next request will take, 0..1; drawn as a ring by the "+". */
    val contextUsage: Float = 0f,
    /** A recording in progress: the composer glows blue. */
    val recording: RecordMode = RecordMode.NONE,
    /** The skill being replayed. */
    val playing: String? = null,
    /** A phone task is running: what is typed is added to it instead of starting a new question. */
    val taskRunning: Boolean = false,
    /** "针对第 2 步：点「搜索」" when a step of a recorded skill is selected for the next message. */
    val focusLabel: String? = null,
)

/** Hold-to-talk. [onDown] returns false when recording could not start. */
class MicActions(
    val onDown: () -> Boolean,
    val onUp: (send: Boolean) -> Unit,
    val onCancelHint: (cancel: Boolean) -> Unit,
    val onToggleVoiceMode: () -> Unit,
)

/** Pictures, documents and the pending reply. */
class AttachActions(
    val onPickImage: () -> Unit,
    /** Opens the camera; the photo taken becomes the pending picture. */
    val onTakePhoto: () -> Unit,
    val onPickFile: () -> Unit,
    val onRemoveAttachment: () -> Unit,
    val onClearReply: () -> Unit,
    val skill: SkillActions = SkillActions(),
)

/** Recording and replaying from the "+" menu, and the recording / step strip above the input. */
class SkillActions(
    val onRecord: () -> Unit = {},
    val onPlay: () -> Unit = {},
    val onStopRecording: () -> Unit = {},
    val onClearFocus: () -> Unit = {},
)

/** What a recorded skill's card can do. */
class SkillCardActions(
    val onFocus: (skill: String, index: Int) -> Unit,
    val onMove: (skill: String, from: Int, to: Int) -> Unit,
    /** Opens a card for review again (null: stop reviewing). */
    val onReview: (skill: String?) -> Unit,
)

/** Everything the composer can ask for. */
class ComposerActions(
    val onDraftChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val mic: MicActions,
    val attach: AttachActions,
)

private val PillShape = RoundedCornerShape(28.dp)

@Composable
fun ChatComposer(
    state: ComposerState,
    actions: ComposerActions,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .recordingGlow(state.recording != RecordMode.NONE),
    ) {
        Column {
            SkillStrip(state, actions.attach)
            PendingStrip(state, actions.attach)
            Row(modifier = Modifier.padding(4.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = actions.mic.onToggleVoiceMode, enabled = !state.sending) {
                    val toggleLabel =
                        if (state.voiceMode) R.string.native_chat_switch_keyboard else R.string.native_chat_switch_voice
                    Icon(
                        if (state.voiceMode) Icons.Default.Keyboard else Icons.Default.Mic,
                        contentDescription = stringResource(toggleLabel),
                    )
                }
                if (state.voiceMode) {
                    HoldToTalk(state, actions.mic, Modifier.weight(1f))
                } else {
                    DraftField(state, actions, focusRequester, Modifier.weight(1f))
                }
                if (LocalTier.current.atLeast(AppTier.OFFICE)) {
                    ContextRing(state.contextUsage)
                    AttachButton(state, actions.attach)
                }
                SendOrStop(state, actions)
            }
        }
    }
}

/** The send arrow; a stop square while a reply is being written (also in voice mode). */
@Composable
private fun SendOrStop(
    state: ComposerState,
    actions: ComposerActions,
) {
    val button = Modifier.padding(start = 4.dp).size(48.dp)
    when {
        state.stoppable -> {
            FilledIconButton(onClick = actions.onStop, modifier = button) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.chat_stop))
            }
        }

        !state.voiceMode -> {
            FilledIconButton(onClick = actions.onSend, enabled = state.canSend, modifier = button) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.native_chat_send),
                )
            }
        }
    }
}

/** The reply being answered and the attachment waiting to go, each with its own ×. */
@Composable
private fun PendingStrip(
    state: ComposerState,
    attach: AttachActions,
) {
    state.replyTo?.let { quote ->
        Row(modifier = Modifier.padding(STRIP_PADDING), verticalAlignment = Alignment.CenterVertically) {
            val labelled = quote.copy(speaker = stringResource(R.string.chat_reply_to, quote.speaker))
            QuoteBlock(labelled, onClick = null, Modifier.weight(1f))
            IconButton(onClick = attach.onClearReply) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_rename_cancel))
            }
        }
    }
    state.attachment?.let { attachment ->
        Row(modifier = Modifier.padding(STRIP_PADDING), verticalAlignment = Alignment.CenterVertically) {
            if (attachment.isImage) {
                Icon(Icons.Default.Image, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(attachment.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            } else {
                FileChip(attachment, onClick = null, Modifier.weight(1f))
            }
            IconButton(onClick = attach.onRemoveAttachment) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_attach_remove))
            }
        }
    }
    if (state.attaching) {
        Row(modifier = Modifier.padding(start = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.chat_attach_reading), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * How full the model's context is: a thin ring, blue while there is room, amber past 75 %, red
 * past 95 %. Sits between the text box and the "+" so it is glanceable but never in the way.
 */
@Composable
private fun ContextRing(usage: Float) {
    val level = ContextBudget.level(usage)
    val color =
        when (level) {
            ContextLevel.OK -> MaterialTheme.colorScheme.primary
            ContextLevel.WARN -> ContextWarn
            ContextLevel.CRITICAL -> MaterialTheme.colorScheme.error
        }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = RING_TRACK_ALPHA)
    val percent = (usage * PERCENT).toInt()
    val label = stringResource(R.string.chat_context_usage, percent)
    // As tall as the icon buttons beside it so the ring shares their centre line, but narrow, so
    // the text box keeps its width.
    Box(
        modifier =
            Modifier
                .size(width = RING_WIDTH, height = RING_TOUCH)
                .semantics { contentDescription = label }
                .drawBehind {
                    val stroke = RING_STROKE.toPx()
                    // A fixed-diameter circle centred in the box, whatever the box's proportions.
                    val diameter = RING_SIZE.toPx() - stroke
                    val arcSize = Size(diameter, diameter)
                    val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                    drawArc(track, 0f, FULL_SWEEP, false, topLeft, arcSize, style = Stroke(stroke))
                    if (usage > 0f) {
                        val used = Stroke(stroke, cap = StrokeCap.Round)
                        drawArc(color, START_ANGLE, FULL_SWEEP * usage, false, topLeft, arcSize, style = used)
                    }
                },
    )
}

/** "+" with a small menu: picture or file. */
@Composable
private fun AttachButton(
    state: ComposerState,
    attach: AttachActions,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = !state.sending && !state.attaching) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chat_attach))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_attach_image)) },
                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                onClick = {
                    open = false
                    attach.onPickImage()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_attach_camera)) },
                leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                onClick = {
                    open = false
                    attach.onTakePhoto()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_attach_file)) },
                leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                onClick = {
                    open = false
                    attach.onPickFile()
                },
            )
            DropdownMenuItem(
                text = { Text("录制操作") },
                leadingIcon = { Icon(Icons.Default.FiberManualRecord, contentDescription = null) },
                enabled = state.recording == RecordMode.NONE,
                onClick = {
                    open = false
                    attach.skill.onRecord()
                },
            )
            DropdownMenuItem(
                text = { Text("播放操作") },
                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                enabled = state.playing == null,
                onClick = {
                    open = false
                    attach.skill.onPlay()
                },
            )
        }
    }
}

/**
 * The text box. Built on the bare text field rather than the Material one: that one is never
 * shorter than 56dp and pads its text by 16dp on each side, which left the 48dp buttons beside
 * it sitting below its centre line and the text with too little room on narrow phones.
 */
@Composable
private fun DraftField(
    state: ComposerState,
    actions: ComposerActions,
    focusRequester: FocusRequester,
    modifier: Modifier,
) {
    val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = state.draft,
        onValueChange = actions.onDraftChange,
        modifier = modifier.focusRequester(focusRequester).heightIn(min = FIELD_HEIGHT),
        enabled = !state.listening,
        textStyle = style,
        maxLines = 5,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (state.canSend) actions.onSend() }),
        decorationBox = { field ->
            // One line is centred on the buttons' own centre; more lines grow upwards from there.
            Box(modifier = Modifier.padding(FIELD_PADDING), contentAlignment = Alignment.CenterStart) {
                if (state.draft.isEmpty()) FittingHint(composerHints(state.taskRunning), style)
                field()
            }
        },
    )
}

/** Press and hold to dictate; release fills the text box, sliding up before release cancels. */
@Composable
private fun HoldToTalk(
    state: ComposerState,
    mic: MicActions,
    modifier: Modifier,
) {
    val cancelSlopPx = with(LocalDensity.current) { CANCEL_SLOP.toPx() }
    val enabled = !state.sending
    val background =
        when {
            state.listening && state.cancelHint -> MaterialTheme.colorScheme.errorContainer
            state.listening -> MaterialTheme.colorScheme.primaryContainer
            else -> Color.Transparent
        }
    Box(
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .background(background, PillShape)
                .holdToTalkGesture(enabled, cancelSlopPx, mic),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text =
                stringResource(
                    when {
                        state.listening && state.cancelHint -> R.string.native_chat_mic_release_cancel
                        state.listening -> R.string.native_chat_mic_release_send
                        else -> R.string.native_chat_hold_to_talk
                    },
                ),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private fun Modifier.holdToTalkGesture(
    enabled: Boolean,
    cancelSlopPx: Float,
    mic: MicActions,
): Modifier =
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (mic.onDown()) {
                val cancelled = trackUntilRelease(down.id, down.position.y, cancelSlopPx, mic.onCancelHint)
                mic.onUp(!cancelled)
            }
        }
    }

/**
 * Follows the pointer until it lifts — or disappears, which also ends the recording — and reports
 * whether it ended slid up past [cancelSlopPx] (the cancel zone).
 */
private suspend fun AwaitPointerEventScope.trackUntilRelease(
    pointerId: PointerId,
    startY: Float,
    cancelSlopPx: Float,
    onCancelChange: (Boolean) -> Unit,
): Boolean {
    var cancelled = false
    var pressed = true
    while (pressed) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId }
        val nowCancel = change != null && startY - change.position.y > cancelSlopPx
        if (change != null && nowCancel != cancelled) {
            cancelled = nowCancel
            onCancelChange(cancelled)
        }
        change?.consume()
        pressed = change?.pressed == true
    }
    return cancelled
}

private val CANCEL_SLOP = 72.dp
private val FIELD_HEIGHT = 48.dp
private val FIELD_PADDING = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
private val RING_TOUCH = 48.dp
private val RING_WIDTH = 28.dp
private val RING_SIZE = 20.dp
private val RING_STROKE = 2.5.dp
private const val RING_TRACK_ALPHA = 0.15f
private const val START_ANGLE = -90f
private const val FULL_SWEEP = 360f
private const val PERCENT = 100
private val STRIP_PADDING = PaddingValues(start = 16.dp, end = 4.dp, top = 8.dp)
