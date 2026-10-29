@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.services.vault.PendingVaultSave
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceValueKind
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.VaultSaveViewModel

/**
 * Asks "存进保险箱吗？" for whatever the single input routed to the vault, on any tab. The dialog is
 * its own secure window, so the value stays out of screenshots, including the agent's.
 */
@Composable
fun VaultSavePrompt(viewModel: VaultSaveViewModel = hiltViewModel()) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(saved) {
        saved?.let {
            Toast.makeText(context, "已存进保险箱：$it", Toast.LENGTH_SHORT).show()
            viewModel.savedShown()
        }
    }
    pending?.let { VaultSaveDialog(it, onSave = viewModel::confirm, onDismiss = viewModel::dismiss) }
}

@Composable
private fun VaultSaveDialog(
    pending: PendingVaultSave,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = pending.draft
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.replaces != null) "更新保险箱里的「${draft.label}」？" else "存进保险箱？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(draft.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    VoiceDraftFormat.forReading(draft),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = if (draft.kind == VoiceValueKind.TEXT) FontFamily.Default else FontFamily.Monospace,
                    modifier = Modifier.semantics { contentDescription = VoiceDraftFormat.forScreenReader(draft) },
                )
                if (draft.sensitive) Text("敏感内容：每次填写都会先问你", style = MaterialTheme.typography.bodySmall)
                (pending.error ?: draft.warning)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Text("不对的话点「不对」，再说一遍。这句话不会发给 AI。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !pending.saving, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(if (pending.saving) "正在保存…" else "对，保存", style = MaterialTheme.typography.titleMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !pending.saving, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("不对", style = MaterialTheme.typography.titleMedium)
            }
        },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}
