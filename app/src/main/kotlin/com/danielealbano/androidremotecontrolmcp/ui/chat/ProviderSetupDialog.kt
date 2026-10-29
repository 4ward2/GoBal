@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderPresets
import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderSetup
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ProviderSetupViewModel

/**
 * Shows the "添加服务商" card whenever the local rules queued one, from any input. Lives at the
 * root next to the vault prompt, so a sentence said to the power-button wake lands here too.
 */
@Composable
fun ProviderSetupPrompt(viewModel: ProviderSetupViewModel = hiltViewModel()) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(saved) {
        saved?.let {
            Toast.makeText(context, "已添加服务商：$it", Toast.LENGTH_SHORT).show()
            viewModel.savedShown()
        }
    }
    pending?.let { ProviderSetupDialog(it, viewModel) }
}

@Composable
private fun ProviderSetupDialog(
    setup: ProviderSetup,
    viewModel: ProviderSetupViewModel,
) {
    val existing = setup.existing
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(if (existing != null) "更新「${existing.name}」" else "添加服务商") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                ServicePicker(setup.draft.presetId, onPick = viewModel::choosePreset)
                DocsStatus(setup)
                if (ProviderPresets.byId(setup.draft.presetId) == null) {
                    CustomServiceFields(setup.draft, viewModel::edit)
                } else {
                    AddressLine(setup.draft.baseUrl)
                }
                KeyField(setup.draft.apiKey, onChange = { key -> viewModel.edit { it.copy(apiKey = key) } })
                KeyCheckSection(setup, onCheck = viewModel::check, onModel = viewModel::chooseModel)
                if (!setup.activeAlreadyUses) UseForActiveRow(setup, viewModel::setUseForActive)
                if (existing != null) Hint("会替换「${existing.name}」原来的密钥，用它的联系人不受影响。")
                setup.error?.let { Hint(it, error = true) }
                Hint("密钥只存在这台手机上。这句话没有发给 AI，也不会出现在聊天记录里。")
            }
        },
        confirmButton = {
            Button(onClick = viewModel::confirm, enabled = setup.canSave, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(if (setup.saving) "正在保存…" else "保存", style = MaterialTheme.typography.titleMedium)
            }
        },
        dismissButton = {
            TextButton(
                onClick = viewModel::dismiss,
                enabled = !setup.saving,
                modifier = Modifier.heightIn(min = 56.dp),
            ) {
                Text("取消", style = MaterialTheme.typography.titleMedium)
            }
        },
        // The key is on screen: keep it out of screenshots and the recents thumbnail.
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}
