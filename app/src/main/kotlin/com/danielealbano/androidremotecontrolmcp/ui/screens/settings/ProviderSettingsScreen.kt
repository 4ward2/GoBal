@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import com.danielealbano.androidremotecontrolmcp.services.chat.RootHelper
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModels
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.LocalModelViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ModelImportViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ProviderForm
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ProviderViewModel

/** What the add / edit dialog can ask for. */
private class DialogActions(
    val onChange: ((ProviderForm) -> ProviderForm) -> Unit,
    val onFetch: () -> Unit,
    val onSave: () -> Unit,
    val onDismiss: () -> Unit,
)

/** 设置 → 服务商: models on the phone, then online services (name, wire format, address, key, models). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProviderViewModel = hiltViewModel(),
    local: LocalModelViewModel = hiltViewModel(),
    importer: ModelImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val localUi by local.ui.collectAsStateWithLifecycle()
    val importUi by importer.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.notice) {
        ui.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.noticeShown()
        }
    }
    LaunchedEffect(localUi.notice) {
        localUi.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            local.noticeShown()
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_providers_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
            },
            windowInsets = WindowInsets(0),
        )
        ProviderList(ui.providers, viewModel) { localModelSection(localUi, local, onAdd = importer::open) }
    }
    if (importUi.open) LocalModelImportDialog(importUi, importer, onDownload = local::add)
    ui.form?.let { form ->
        ProviderDialog(
            form = form,
            fetching = ui.fetching,
            actions = DialogActions(viewModel::edit, viewModel::fetchModels, viewModel::save, viewModel::cancel),
        )
    }
}

@Composable
private fun ProviderList(
    all: List<ModelProvider>,
    viewModel: ProviderViewModel,
    localSection: LazyListScope.() -> Unit,
) {
    // The on-device entry is managed by the section above; it is listed here only for contacts to pick.
    val providers = all.filterNot { LocalModels.isLocal(it) }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        localSection()
        item(key = "remote-title") { SectionTitle("在线服务商") }
        if (providers.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.provider_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
        items(providers, key = { it.id }) { provider ->
            ProviderRow(
                provider,
                onEdit = { viewModel.startEdit(provider) },
                onDelete = { viewModel.delete(provider.id) },
            )
        }
        item(key = "add") {
            FilledTonalButton(
                onClick = viewModel::startAdd,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.provider_add), style = MaterialTheme.typography.titleMedium)
            }
            if (RootHelper.present()) {
                Text(
                    stringResource(R.string.provider_on_device_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: ModelProvider,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Surface(
        onClick = onEdit,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        ListItem(
            headlineContent = { Text(provider.name, style = MaterialTheme.typography.titleMedium) },
            supportingContent = {
                val kind = if (provider.kind == ProviderKind.OPENAI) "OpenAI" else "Anthropic"
                Text("$kind · ${provider.baseUrl} · ${provider.models.size} 个模型")
            },
            trailingContent = {
                val label = stringResource(R.string.provider_delete, provider.name)
                IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, contentDescription = label) }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.provider_delete, provider.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onDelete()
                }) { Text(stringResource(R.string.character_delete_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.chat_rename_cancel)) }
            },
        )
    }
}

/** Add / edit; the key field is masked and the dialog window refuses screenshots. */
@Composable
private fun ProviderDialog(
    form: ProviderForm,
    fetching: Boolean,
    actions: DialogActions,
) {
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(stringResource(if (form.isNew) R.string.provider_add else R.string.settings_providers_title)) },
        text = { ProviderFields(form, fetching, actions) },
        confirmButton = {
            TextButton(onClick = actions.onSave, enabled = form.valid) {
                Text(stringResource(R.string.chat_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.chat_rename_cancel)) }
        },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}

@Composable
private fun ProviderFields(
    form: ProviderForm,
    fetching: Boolean,
    actions: DialogActions,
) {
    val onChange = actions.onChange
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = form.name,
            onValueChange = { v -> onChange { it.copy(name = v) } },
            label = { Text(stringResource(R.string.provider_name)) },
            singleLine = true,
        )
        KindChips(form.kind, onPick = { kind -> onChange { it.copy(kind = kind) } })
        OutlinedTextField(
            value = form.baseUrl,
            onValueChange = { v -> onChange { it.copy(baseUrl = v) } },
            label = { Text(stringResource(R.string.provider_base_url)) },
            placeholder = { Text("https://api.openai.com/v1") },
            singleLine = true,
        )
        KeyField(form.apiKey, onChange = { v -> onChange { it.copy(apiKey = v) } })
        OutlinedTextField(
            value = form.models,
            onValueChange = { v -> onChange { it.copy(models = v) } },
            label = { Text(stringResource(R.string.provider_models)) },
            minLines = 2,
        )
        OutlinedTextField(
            value = form.contextWindow,
            onValueChange = { v -> onChange { it.copy(contextWindow = v.filter(Char::isDigit)) } },
            label = { Text(stringResource(R.string.provider_context_window)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        TextButton(onClick = actions.onFetch, enabled = form.valid && !fetching) {
            if (fetching) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.provider_fetch_models))
        }
    }
}

@Composable
private fun KindChips(
    current: ProviderKind,
    onPick: (ProviderKind) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.provider_kind), style = MaterialTheme.typography.labelLarge)
        ProviderKind.entries.forEach { kind ->
            val label =
                if (kind == ProviderKind.OPENAI) R.string.provider_kind_openai else R.string.provider_kind_anthropic
            FilterChip(selected = current == kind, onClick = { onPick(kind) }, label = { Text(stringResource(label)) })
        }
    }
}

@Composable
private fun KeyField(
    value: String,
    onChange: (String) -> Unit,
) {
    var showKey by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.provider_api_key)) },
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                val icon = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility
                Icon(icon, contentDescription = null)
            }
        },
    )
}
