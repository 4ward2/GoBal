@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultFillRecord
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultSlot
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceLanguage
import com.danielealbano.androidremotecontrolmcp.ui.chat.VoiceDraftFormat
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AssistViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.VaultViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows what is kept and lets it be fixed by hand. Adding and updating happen by talking to the
 * assistant (the app's single voice input); typing is the fallback for what must not be said aloud.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VaultViewModel = hiltViewModel(),
    assistViewModel: AssistViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val language by assistViewModel.voiceLanguage.collectAsStateWithLifecycle()

    SecureWindow()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("私人保险箱") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = { ManualEntryMenu(onManualAdd = viewModel::startNew) },
            windowInsets = WindowInsets(0),
        )
        VaultList(viewModel = viewModel, language = language, modifier = Modifier.weight(1f))
    }

    form?.let { current ->
        VaultEditDialog(
            form = current,
            onChange = viewModel::updateForm,
            onSave = viewModel::save,
            onDismiss = viewModel::dismissForm,
        )
    }
}

@Composable
private fun ManualEntryMenu(onManualAdd: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("手动输入（适合密码）") },
                onClick = {
                    open = false
                    onManualAdd()
                },
            )
        }
    }
}

@Composable
private fun VaultList(
    viewModel: VaultViewModel,
    language: VoiceLanguage,
    modifier: Modifier = Modifier,
) {
    val slots by viewModel.slots.collectAsStateWithLifecycle()
    val log by viewModel.fillLog.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SayItHint(language) }
        item { PrivacyNote(loadError, onReset = viewModel::resetVault) }
        item {
            Text(
                if (slots.isEmpty()) "还没有内容。" else "已保存（点一下可以手动修改）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(slots, key = { it.id }) { slot ->
            SlotCard(slot = slot, onEdit = { viewModel.startEdit(slot.id) }, onDelete = { viewModel.delete(slot.id) })
        }
        if (log.isNotEmpty()) {
            item { Text("最近的填写记录", style = MaterialTheme.typography.titleMedium) }
            items(log.take(LOG_ROWS), key = { "${it.timestamp}-${it.slotId}-${it.packageName}" }) {
                LogRow(it, slots)
            }
        }
        item { Row(Modifier.padding(bottom = 24.dp)) {} }
    }
}

private const val LOG_ROWS = 20

/** Where adding happens now: the one voice input, with sentences that work in the chosen language. */
@Composable
private fun SayItHint(language: VoiceLanguage) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("跟助手说一句就能存", style = MaterialTheme.typography.titleLarge)
            Text("在「对话」页按住麦克风，或长按电源键，这样说：", style = MaterialTheme.typography.bodyMedium)
            VoiceDraftFormat.examples(language).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                "手机会先请你确认，这些话不会发给 AI。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PrivacyNote(
    loadError: String?,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "只存在这台手机上，加密保存。助手只看得到名称，内容由手机直接填进输入框。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        loadError?.let { ResetRow(it, onReset) }
    }
}

@Composable
private fun ResetRow(
    error: String,
    onReset: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Text(
        "$error。可能是恢复了备份或文件损坏，加密密钥不在这台手机上，内容无法找回。",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = { confirm = true }) { Text("清空保险箱并重新开始") }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("清空保险箱？") },
            text = { Text("所有已保存的内容都会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onReset()
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}

/** One saved entry: tap to fix it by hand; delete lives in the ⋮ menu. */
@Composable
private fun SlotCard(
    slot: VaultSlot,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(slot.label, style = MaterialTheme.typography.titleMedium)
                Text(slotSummary(slot), style = MaterialTheme.typography.bodySmall)
            }
            SlotMenu(slot.label, onEdit = onEdit, onDelete = { confirmDelete = true })
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除「${slot.label}」？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

private fun slotSummary(slot: VaultSlot): String =
    buildString {
        append(slot.category.label)
        if (slot.sensitive) append(" · 每次填写先问你")
        if (slot.allowedPackages.isNotEmpty()) append(" · 仅限 ${slot.allowedPackages.size} 个应用")
    }

@Composable
private fun SlotMenu(
    label: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "「$label」的更多操作")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("手动修改") },
                onClick = {
                    open = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun LogRow(
    record: VaultFillRecord,
    slots: List<VaultSlot>,
) {
    val label = slots.firstOrNull { it.id == record.slotId }?.label ?: record.slotId
    val time =
        remember(record.timestamp) {
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(record.timestamp))
        }
    Text(
        "$time  $label → ${record.packageName}  ${record.outcome}",
        style = MaterialTheme.typography.bodySmall,
    )
}
