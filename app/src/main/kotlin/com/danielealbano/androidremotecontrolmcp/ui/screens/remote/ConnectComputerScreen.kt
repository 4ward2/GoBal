@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.remote

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R

/** Which OS tab is selected. */
private enum class OsTab {
    Windows,
    MacOS,
    Linux,
}

private const val STEPS_PER_PLATFORM = 4
private const val LAST_STEP = STEPS_PER_PLATFORM - 1
private const val TOAST_DURATION = Toast.LENGTH_SHORT

/**
 * Step-by-step guide to install Paseo on the user's computer and pair it with the phone.
 * Provides platform-specific instructions, copyable commands, and action buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectComputerScreen(
    onBack: () -> Unit,
    onScan: () -> Unit,
    onPasteLink: () -> Unit,
    onManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.remote_connect_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    val back = stringResource(R.string.settings_back)
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = back)
                }
            },
            windowInsets = WindowInsets(0),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "tabs") {
                OsTabRow(selectedTab) { selectedTab = it }
            }
            when (OsTab.entries[selectedTab]) {
                OsTab.Windows -> {
                    items(STEPS_PER_PLATFORM, key = { "win-$it" }) { step ->
                        WindowsStep(step)
                    }
                }

                OsTab.MacOS -> {
                    items(STEPS_PER_PLATFORM, key = { "mac-$it" }) { step ->
                        MacosStep(step)
                    }
                }

                OsTab.Linux -> {
                    items(STEPS_PER_PLATFORM, key = { "linux-$it" }) { step ->
                        LinuxStep(step, context) { copied = true }
                    }
                }
            }
            item(key = "info") {
                InfoCard()
            }
        }
        ActionButtons(onScan, onPasteLink, onManual, modifier = Modifier.padding(16.dp))
    }
}

/** OS tab row (Windows, macOS, Linux). */
@Composable
private fun OsTabRow(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = selected) {
        Tab(
            selected = selected == 0,
            onClick = { onSelect(0) },
            text = { Text(stringResource(R.string.remote_connect_tab_windows)) },
        )
        Tab(
            selected = selected == 1,
            onClick = { onSelect(1) },
            text = { Text(stringResource(R.string.remote_connect_tab_macos)) },
        )
        Tab(
            selected = selected == 2,
            onClick = { onSelect(2) },
            text = { Text(stringResource(R.string.remote_connect_tab_linux)) },
        )
    }
}

/** Windows step (1-4). */
@Composable
private fun WindowsStep(step: Int) {
    when (step) {
        0 -> {
            StepCard(
                title = stringResource(R.string.remote_connect_step_1_title),
                content = stringResource(R.string.remote_connect_windows_step_1),
            )
        }

        1 -> {
            StepCard(
                title = stringResource(R.string.remote_connect_step_2_title),
                content = stringResource(R.string.remote_connect_windows_step_2),
            )
        }

        2 -> {
            StepCard(
                title = stringResource(R.string.remote_connect_step_3_title),
                content = stringResource(R.string.remote_connect_windows_step_3),
            )
        }

        LAST_STEP -> {
            StepCard(
                title = stringResource(R.string.remote_connect_step_4_title),
                content = stringResource(R.string.remote_connect_windows_step_4),
            )
        }
    }
}

/** macOS step (1-4). */
@Composable
private fun MacosStep(step: Int) {
    when (step) {
        0 -> {
            StepCard(
                title = stringResource(R.string.remote_connect_step_1_title),
                content = stringResource(R.string.remote_connect_macos_step_1),
            )
        }

        1 -> {
            StepCard(
                title = stringResource(R.string.remote_connect_step_2_title),
                content = stringResource(R.string.remote_connect_macos_step_2),
            )
        }

        2 -> {
            StepCard(
                title = stringResource(R.string.remote_connect_step_3_title),
                content = stringResource(R.string.remote_connect_macos_step_3),
            )
        }

        LAST_STEP -> {
            StepCard(
                title = stringResource(R.string.remote_connect_step_4_title),
                content = stringResource(R.string.remote_connect_macos_step_4),
            )
        }
    }
}

/** Linux step (1-4). */
@Composable
private fun LinuxStep(
    step: Int,
    context: Context,
    onCopied: () -> Unit,
) {
    when (step) {
        0 -> {
            StepCardWithCommand(
                title = stringResource(R.string.remote_connect_step_1_title),
                content = stringResource(R.string.remote_connect_linux_step_1),
                command = stringResource(R.string.remote_connect_linux_step_1_cmd),
                context = context,
                onCopied = onCopied,
            )
        }

        1 -> {
            StepCardWithCommand(
                title = stringResource(R.string.remote_connect_step_2_title),
                content = stringResource(R.string.remote_connect_linux_step_2),
                command = stringResource(R.string.remote_connect_linux_step_2_cmd),
                context = context,
                onCopied = onCopied,
            )
        }

        2 -> {
            StepCardWithCommand(
                title = stringResource(R.string.remote_connect_step_3_title),
                content = stringResource(R.string.remote_connect_linux_step_3),
                command = stringResource(R.string.remote_connect_linux_step_3_cmd),
                context = context,
                onCopied = onCopied,
            )
        }

        LAST_STEP -> {
            StepCardWithCommand(
                title = stringResource(R.string.remote_connect_step_4_title),
                content = stringResource(R.string.remote_connect_linux_step_4),
                command = stringResource(R.string.remote_connect_linux_step_4_cmd),
                context = context,
                onCopied = onCopied,
            )
        }
    }
}

/** Simple text step card. */
@Composable
private fun StepCard(
    title: String,
    content: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Step card with a copyable command. */
@Composable
private fun StepCardWithCommand(
    title: String,
    content: String,
    command: String,
    context: Context,
    onCopied: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            CommandBlock(command = command, context = context, onCopied = onCopied)
        }
    }
}

/** Monospace command block with copy button. */
@Composable
private fun CommandBlock(
    command: String,
    context: Context,
    onCopied: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = command,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                maxLines = 1,
            )
            IconButton(
                onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = android.content.ClipData.newPlainText("command", command)
                    clipboard.setPrimaryClip(clip)
                    val copiedText = context.getString(R.string.remote_connect_helper_copied)
                    Toast.makeText(context, copiedText, TOAST_DURATION).show()
                    onCopied()
                },
                modifier = Modifier.padding(0.dp),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = context.getString(R.string.remote_connect_helper_copy),
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
}

/** Information and troubleshooting card. */
@Composable
private fun InfoCard() {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.remote_connect_info_relay),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.remote_connect_info_secure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Bottom action buttons (scan, paste, manual). */
@Composable
private fun ActionButtons(
    onScan: () -> Unit,
    onPasteLink: () -> Unit,
    onManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onScan,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.remote_connect_scan_qr))
        }
        OutlinedButton(
            onClick = onPasteLink,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.remote_connect_paste_link))
        }
        OutlinedButton(
            onClick = onManual,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.remote_connect_manual_address))
        }
    }
}
