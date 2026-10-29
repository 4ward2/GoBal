@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultCategory
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.VaultForm

@Composable
internal fun VaultEditDialog(
    form: VaultForm,
    onChange: ((VaultForm) -> VaultForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (form.isNew) "添加" else "编辑「${form.label}」") },
        text = { EditFields(form, onChange) },
        confirmButton = { TextButton(onClick = onSave) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        // A dialog is its own window: the activity's FLAG_SECURE does not cover it.
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditFields(
    form: VaultForm,
    onChange: ((VaultForm) -> VaultForm) -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = form.label,
            onValueChange = { v -> onChange { it.copy(label = v) } },
            label = { Text("名称（助手看得到）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = form.id,
            onValueChange = { v -> onChange { it.copy(id = v.lowercase()) } },
            label = { Text("槽位名（英文，助手用它指代）") },
            singleLine = true,
            enabled = form.isNew,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VaultCategory.entries.forEach { c ->
                FilterChip(
                    selected = form.category == c,
                    onClick = { onChange { it.copy(category = c) } },
                    label = { Text(c.label) },
                )
            }
        }
        SecretField(form, onChange)
        SensitiveRow(form, onChange)
        OutlinedTextField(
            value = form.allowedPackages,
            onValueChange = { v -> onChange { it.copy(allowedPackages = v) } },
            label = { Text("只允许填入这些应用（包名，可留空）") },
            placeholder = { Text("com.taobao.taobao") },
        )
        form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SecretField(
    form: VaultForm,
    onChange: ((VaultForm) -> VaultForm) -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = form.value,
        onValueChange = { v -> onChange { it.copy(value = v) } },
        label = { Text(if (form.isNew) "内容（助手看不到）" else "新内容（留空则保持不变）") },
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { reveal = !reveal }) {
                Icon(
                    if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (reveal) "隐藏内容" else "显示内容",
                )
            }
        },
        minLines = if (form.category == VaultCategory.ADDRESS) 2 else 1,
    )
}

@Composable
private fun SensitiveRow(
    form: VaultForm,
    onChange: ((VaultForm) -> VaultForm) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("敏感", style = MaterialTheme.typography.bodyLarge)
            Text("每次填写都要在手机上确认", style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = form.sensitive || form.category == VaultCategory.CREDENTIAL,
            onCheckedChange = { v -> onChange { it.copy(sensitive = v) } },
            enabled = form.category != VaultCategory.CREDENTIAL,
        )
    }
}
