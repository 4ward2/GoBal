@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import android.app.Activity
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.call.CallPhase
import com.danielealbano.androidremotecontrolmcp.services.call.CallUiState
import com.danielealbano.androidremotecontrolmcp.ui.call.CameraPreview
import com.danielealbano.androidremotecontrolmcp.ui.call.rememberCameraFrames
import com.danielealbano.androidremotecontrolmcp.ui.components.AssistantAvatar
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.CallViewModel
import kotlinx.coroutines.delay

/**
 * The call, laid out like a phone's: portrait and name on top, what each side is saying in the
 * middle, mute / speaker / hang-up at the bottom. A voice call is a translucent layer over the
 * chat; a video call puts the camera picture behind it all.
 */
@Composable
fun CallScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val callee by viewModel.callee.collectAsStateWithLifecycle()
    var front by rememberSaveable { mutableStateOf(true) }
    KeepScreenOn()
    BackHandler { viewModel.hangUp() }

    Box(modifier = modifier.fillMaxSize().swallowTouches()) {
        if (ui.video) {
            CameraBackdrop(front, viewModel)
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = VOICE_SCRIM)))
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            val portrait = remember(callee.avatar) { callee.avatar?.asImageBitmap() }
            AssistantAvatar(name = callee.name, size = if (ui.video) PORTRAIT_VIDEO else PORTRAIT, image = portrait)
            Spacer(Modifier.height(16.dp))
            Text(callee.name, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(statusLine(ui), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = DIM))
            ui.downloading?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.weight(1f))
            Captions(ui)
            Spacer(Modifier.height(32.dp))
            Controls(ui, ui.video, viewModel, onFlip = { front = !front })
        }
    }
}

/** The live camera, shaded at the top and bottom so the name, captions and buttons stay readable. */
@Composable
private fun CameraBackdrop(
    front: Boolean,
    viewModel: CallViewModel,
) {
    val frames = rememberCameraFrames()
    DisposableEffect(frames) {
        viewModel.attachCamera(frames)
        onDispose { viewModel.attachCamera(null) }
    }
    Box(Modifier.fillMaxSize()) {
        CameraPreview(frames, front, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = EDGE_SHADE),
                    SHADE_TOP_END to Color.Transparent,
                    SHADE_BOTTOM_START to Color.Transparent,
                    1f to Color.Black.copy(alpha = EDGE_SHADE),
                ),
            ),
        )
    }
}

/** The call lies over the chat: touches must stop here instead of reaching the page underneath. */
private fun Modifier.swallowTouches(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
            }
        }
    }

/** "在听 · 03:21", or the connection / download stage before that. */
@Composable
private fun statusLine(ui: CallUiState): String {
    val downloading = ui.downloading
    val phase =
        when (ui.phase) {
            CallPhase.LISTENING -> R.string.call_listening
            CallPhase.THINKING -> R.string.call_thinking
            CallPhase.SPEAKING -> R.string.call_speaking
        }
    return when {
        downloading != null -> stringResource(R.string.call_downloading, (downloading * PERCENT).toInt())
        !ui.connected -> stringResource(R.string.call_connecting)
        else -> "${stringResource(phase)} · ${Elapsed(ui.startedAt)}"
    }
}

/** A ticking mm:ss since the call began. */
@Composable
private fun Elapsed(startedAt: Long): String {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TIMER_MS)
            now = SystemClock.elapsedRealtime()
        }
    }
    val seconds = ((now - startedAt) / MS_PER_S).coerceAtLeast(0)
    return "%02d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

@Composable
private fun Captions(ui: CallUiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        ui.error?.let {
            Caption(it, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        }
        if (ui.assistantLine.isNotBlank()) Caption(ui.assistantLine, Color.White.copy(alpha = BUBBLE), Color.White)
        if (ui.userLine.isNotBlank()) {
            Caption(ui.userLine, MaterialTheme.colorScheme.primary.copy(alpha = BUBBLE), Color.White)
        }
        if (ui.connected && !ui.echoCanceller) {
            Text(
                stringResource(R.string.call_no_aec),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = DIM),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Caption(
    text: String,
    background: Color,
    color: Color,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(background)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun Controls(
    ui: CallUiState,
    video: Boolean,
    viewModel: CallViewModel,
    onFlip: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(
            icon = if (ui.muted) Icons.Default.MicOff else Icons.Default.Mic,
            label = stringResource(if (ui.muted) R.string.call_unmute else R.string.call_mute),
            onClick = viewModel::toggleMute,
        )
        RoundButton(
            icon = if (ui.speakerphone) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
            label = stringResource(if (ui.speakerphone) R.string.call_earpiece else R.string.call_speaker),
            onClick = viewModel::toggleSpeakerphone,
        )
        if (video) {
            RoundButton(Icons.Default.Cameraswitch, stringResource(R.string.call_flip_camera), onFlip)
        }
        RoundButton(
            icon = Icons.Default.CallEnd,
            label = stringResource(R.string.call_hang_up),
            onClick = viewModel::hangUp,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    color: Color = Color.White.copy(alpha = BUBBLE),
) {
    FilledIconButton(
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
        modifier = Modifier.size(BUTTON),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
    }
}

/** A call should not dim the screen while the person is listening. */
@Composable
private fun KeepScreenOn() {
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private val PORTRAIT = 120.dp
private val PORTRAIT_VIDEO = 72.dp
private val BUTTON = 64.dp
private const val DIM = 0.7f
private const val VOICE_SCRIM = 0.62f
private const val EDGE_SHADE = 0.55f
private const val SHADE_TOP_END = 0.3f
private const val SHADE_BOTTOM_START = 0.6f
private const val BUBBLE = 0.22f
private const val PERCENT = 100
private const val TIMER_MS = 1000L
private const val MS_PER_S = 1000L
private const val SECONDS_PER_MINUTE = 60
