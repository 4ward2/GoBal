@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.services.skill.Skill
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillText
import com.danielealbano.androidremotecontrolmcp.ui.VoiceWakeActivity
import com.danielealbano.androidremotecontrolmcp.ui.chat.PlaySheet
import com.danielealbano.androidremotecontrolmcp.ui.chat.RecordModeDialog
import com.danielealbano.androidremotecontrolmcp.ui.chat.SkillCardActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.SkillParamsDialog
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.NativeChatUiState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.NativeChatViewModel

/** Which recording / replay dialog is open in the chat. */
@Stable
class SkillHostState {
    var recordDialog by mutableStateOf(false)
    var playSheet by mutableStateOf(false)
    var paramsFor by mutableStateOf<Skill?>(null)
}

@Composable
fun rememberSkillHost(): SkillHostState = remember { SkillHostState() }

/**
 * The chat's recording and replay dialogs. AI recording opens the voice wake (its glow turns blue
 * and what the user says goes to the phone agent); manual recording goes to the home screen, where
 * the floating ball takes over. A recording that finished while the chat was away is picked up
 * whenever the chat comes back to the front.
 */
@Composable
fun SkillHost(
    state: SkillHostState,
    ui: NativeChatUiState,
    chat: NativeChatViewModel,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { chat.skills.checkReview() }
    }
    if (state.recordDialog) {
        RecordModeDialog(
            onPick = { ai ->
                state.recordDialog = false
                startRecording(context, chat, ai)
            },
            onDismiss = { state.recordDialog = false },
        )
    }
    if (state.playSheet) {
        PlaySheet(
            skills =
                ui.skill.skills.values
                    .sortedByDescending { it.updatedAt },
            onPick = { skill ->
                state.playSheet = false
                if (skill.params.isEmpty()) chat.skills.play(skill.name, emptyMap()) else state.paramsFor = skill
            },
            onDismiss = { state.playSheet = false },
        )
    }
    state.paramsFor?.let { skill ->
        SkillParamsDialog(
            skill = skill,
            onRun = { values ->
                state.paramsFor = null
                chat.skills.play(skill.name, values)
            },
            onDismiss = { state.paramsFor = null },
        )
    }
}

private fun startRecording(
    context: Context,
    chat: NativeChatViewModel,
    ai: Boolean,
) {
    if (!chat.skills.startRecording(ai, canWatchScreen = McpAccessibilityService.instance != null)) return
    val next =
        if (ai) {
            Intent(context, VoiceWakeActivity::class.java)
        } else {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        }
    context.startActivity(next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun skillCardActions(chat: NativeChatViewModel) =
    SkillCardActions(
        onFocus = chat.skills::focus,
        onMove = chat.skills::reorder,
        onReview = chat.skills::review,
    )

/** "针对第 2 步：点「搜索」" for the composer, when a step of the reviewed skill is selected. */
fun focusLabel(ui: NativeChatUiState): String? {
    val index = ui.skill.focus ?: return null
    val step =
        ui.skill.reviewing
            ?.let(ui.skill.skills::get)
            ?.steps
            ?.getOrNull(index)
    return step?.let { "针对第 ${index + 1} 步：${SkillText.describe(it)}" }
}
