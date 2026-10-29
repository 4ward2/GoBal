@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.shell.PairNotice
import com.danielealbano.androidremotecontrolmcp.services.shell.PairPhase
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellPairingService
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellStep
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ShellCheck
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ShellSetupUi
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ShellSetupViewModel
import com.danielealbano.androidremotecontrolmcp.utils.SpecialAccess

/** The system pages each step sends the user to. */
private object ShellPages {
    /** Makes the developer-options page scroll to, and flash, the wireless debugging row. */
    private const val HIGHLIGHT = ":settings:fragment_args_key"
    private const val WIRELESS_ROW = "toggle_adb_wireless"

    fun aboutPhone(context: Context) {
        val about = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        SpecialAccess.open(context, listOf(about, Intent(Settings.ACTION_SETTINGS)))
    }

    fun wifi(context: Context) =
        SpecialAccess.open(context, listOf(Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_SETTINGS)))

    fun wirelessDebugging(context: Context) {
        val developer = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(HIGHLIGHT, WIRELESS_ROW)
        SpecialAccess.open(context, listOf(developer, Intent(Settings.ACTION_SETTINGS)))
    }
}

/** One row of the list: what the step is called, what to do, and the button that takes the user there. */
private data class StepText(
    val title: Int,
    val body: Int,
    val button: Int,
)

private fun textOf(step: ShellStep): StepText =
    when (step) {
        ShellStep.NOTIFICATIONS -> {
            StepText(R.string.shell_step_notify, R.string.shell_step_notify_body, R.string.shell_step_notify_button)
        }

        ShellStep.DEVELOPER -> {
            StepText(
                R.string.shell_step_developer,
                R.string.shell_step_developer_body,
                R.string.shell_step_developer_button,
            )
        }

        ShellStep.WIFI -> {
            StepText(R.string.shell_step_wifi, R.string.shell_step_wifi_body, R.string.shell_step_wifi_button)
        }

        ShellStep.WIRELESS -> {
            StepText(
                R.string.shell_step_wireless,
                R.string.shell_step_wireless_body,
                R.string.shell_step_wireless_button,
            )
        }

        ShellStep.PAIR -> {
            StepText(R.string.shell_step_pair, R.string.shell_step_pair_body, R.string.shell_step_pair_button)
        }

        ShellStep.READY -> {
            StepText(R.string.shell_step_ready, R.string.shell_step_ready_body, R.string.shell_step_ready_button)
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellSetupScreen(
    onBack: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShellSetupViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    RefreshOnResume(viewModel::refresh)
    val act: (ShellStep) -> Unit = { step ->
        when (step) {
            ShellStep.NOTIFICATIONS -> {
                onRequestNotificationPermission()
            }

            ShellStep.DEVELOPER -> {
                ShellPages.aboutPhone(context)
            }

            ShellStep.WIFI -> {
                ShellPages.wifi(context)
            }

            ShellStep.WIRELESS -> {
                ShellPages.wirelessDebugging(context)
            }

            ShellStep.PAIR -> {
                ShellPairingService.start(context.applicationContext)
                ShellPages.wirelessDebugging(context)
            }

            ShellStep.READY -> {
                viewModel.verify()
            }
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_shell_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.shell_back))
                }
            },
            windowInsets = WindowInsets(0),
        )
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.shell_intro), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.shell_caution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
            ShellStep.entries.forEach { step -> StepCard(step, ui, onAct = { act(step) }) }
        }
    }
}

/** The user comes back from the system's settings with something changed: look again each time. */
@Composable
private fun RefreshOnResume(refresh: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun StepCard(
    step: ShellStep,
    ui: ShellSetupUi,
    onAct: () -> Unit,
) {
    val done = step.ordinal < ui.step.ordinal || (step == ShellStep.READY && ui.check == ShellCheck.WORKS)
    val current = step == ui.step
    val text = textOf(step)
    val tone = if (current) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface
    Surface(color = tone, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = stringResource(if (done) R.string.shell_done else R.string.shell_todo),
                    tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(text.title), style = MaterialTheme.typography.titleMedium)
            }
            if (current) CurrentStep(step, text, ui, onAct)
        }
    }
}

@Composable
private fun CurrentStep(
    step: ShellStep,
    text: StepText,
    ui: ShellSetupUi,
    onAct: () -> Unit,
) {
    val body =
        when {
            step == ShellStep.READY -> readyText(ui.check)
            step == ShellStep.PAIR && ui.phase != PairPhase.IDLE -> pairingText(ui.phase)
            else -> stringResource(text.body)
        }
    Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, start = 36.dp))
    val busy = step == ShellStep.READY && ui.check == ShellCheck.CHECKING
    FilledTonalButton(
        onClick = onAct,
        enabled = !busy,
        modifier = Modifier.padding(top = 12.dp, start = 36.dp).heightIn(min = 48.dp),
    ) {
        Text(stringResource(text.button))
    }
}

private fun pairingText(phase: PairPhase): String = PairNotice.title(phase) + "\n" + PairNotice.text(phase)

@Composable
private fun readyText(check: ShellCheck): String =
    stringResource(
        when (check) {
            ShellCheck.WORKS -> R.string.shell_ready_works
            ShellCheck.FAILED -> R.string.shell_ready_failed
            else -> R.string.shell_step_ready_body
        },
    )
