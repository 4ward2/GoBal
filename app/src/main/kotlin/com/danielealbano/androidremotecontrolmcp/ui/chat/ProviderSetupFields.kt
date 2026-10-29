@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import com.danielealbano.androidremotecontrolmcp.services.chat.ContextBudget
import com.danielealbano.androidremotecontrolmcp.services.provider.DocsImport
import com.danielealbano.androidremotecontrolmcp.services.provider.KeyCheck
import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderDraft
import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderPresets
import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderSetup
import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderSetupLogic
import kotlinx.coroutines.launch

private const val CUSTOM = "自定义"

/** Which service: one of the presets, or "自定义" for any other OpenAI / Anthropic compatible address. */
@Composable
internal fun ServicePicker(
    presetId: String?,
    onPick: (String?) -> Unit,
) {
    val options = ProviderPresets.ALL.map { it.id to it.name } + (null to CUSTOM)
    Choice(
        label = "服务商",
        current = ProviderPresets.byId(presetId)?.name ?: CUSTOM,
        options = options,
        onPick = onPick,
    )
}

/** A labelled drop-down of [options] (value to text). */
@Composable
private fun <T> Choice(
    label: String,
    current: String,
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            Surface(
                onClick = { open = true },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(current, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = {
                        open = false
                        onPick(value)
                    })
                }
            }
        }
    }
}

/** A preset's address, shown so the user can see where the key will be sent. */
@Composable
internal fun AddressLine(url: String) {
    Hint("地址：$url")
}

/** Name, address and API format for a service without a preset. */
@Composable
internal fun CustomServiceFields(
    draft: ProviderDraft,
    onEdit: ((ProviderDraft) -> ProviderDraft) -> Unit,
) {
    val name = rememberLocalText(draft.name)
    val url = rememberLocalText(draft.baseUrl)
    OutlinedTextField(
        value = name.text,
        onValueChange = { v ->
            name.typed(v, v)
            onEdit { it.copy(name = v) }
        },
        label = { Text("名称") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = url.text,
        onValueChange = { v ->
            url.typed(v, v.trim())
            onEdit { it.copy(baseUrl = v.trim()) }
        },
        label = { Text("地址（通常以 /v1 结尾）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = draft.kind == ProviderKind.OPENAI,
            onClick = { onEdit { it.copy(kind = ProviderKind.OPENAI) } },
            label = { Text("OpenAI") },
        )
        FilterChip(
            selected = draft.kind == ProviderKind.ANTHROPIC,
            onClick = { onEdit { it.copy(kind = ProviderKind.ANTHROPIC) } },
            label = { Text("Anthropic") },
        )
    }
}

/** The key, hidden by default, with a paste button: keys are copied from a web page, not typed. */
@Composable
internal fun KeyField(
    key: String,
    onChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val field = rememberLocalText(key)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    OutlinedTextField(
        value = field.text,
        onValueChange = {
            field.typed(it, it.trim())
            onChange(it.trim())
        },
        label = { Text("API 密钥") },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "隐藏密钥" else "显示密钥",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onClick = {
        scope.launch {
            val clip = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
            clip
                ?.getItemAt(0)
                ?.text
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(onChange)
        }
    }) {
        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("粘贴密钥", modifier = Modifier.padding(start = 6.dp))
    }
}

/** Whether the key works, and once it does, which of the service's models to use. */
@Composable
internal fun KeyCheckSection(
    setup: ProviderSetup,
    onCheck: () -> Unit,
    onModel: (String) -> Unit,
) {
    when (val check = setup.check) {
        KeyCheck.Idle -> {
            if (setup.canCheck) TextButton(onClick = onCheck) { Text("验证密钥") } else Hint("填好密钥后会自动验证。")
        }

        KeyCheck.Checking -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("正在验证密钥…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        is KeyCheck.Ok -> {
            Text(
                (if (setup.keyUnproven) "✓ 接上了" else "✓ 密钥可用") + "，找到 ${check.models.size} 个对话模型",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (setup.keyUnproven) Hint("这家的模型列表不用密钥也能读到，密钥要等第一次对话时才能确认。")
            if (check.models.isNotEmpty()) {
                // What the service reported, else the model-family estimate the context ring will use.
                val provider = ProviderSetupLogic.toProvider(setup, newId = "")
                val labelOf = { id: String -> "$id · ${ContextBudget.label(ContextBudget.window(provider, id))} 上下文" }
                Choice(
                    label = "用哪个模型",
                    current = labelOf(setup.model),
                    options = check.models.map { it.id to labelOf(it.id) },
                    onPick = onModel,
                )
            }
        }

        is KeyCheck.Failed -> {
            Hint("验证没通过：${check.message}", error = true)
            TextButton(onClick = onCheck) { Text("再试一次") }
        }
    }
}

/**
 * Where the address came from when the user sent a documentation link, or what to send when the
 * card could not tell which service this is.
 */
@Composable
internal fun DocsStatus(setup: ProviderSetup) {
    val draft = setup.draft
    when (val docs = setup.docs) {
        is DocsImport.Reading -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("正在读取 ${docs.host} 的文档…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        is DocsImport.Found -> {
            Hint("接口地址是从 ${docs.host} 的文档里读到的，不对可以直接改。")
        }

        is DocsImport.Failed -> {
            Hint(docs.message, error = true)
        }

        DocsImport.None -> {
            if (draft.presetId == null && draft.baseUrl.isBlank()) {
                Hint("没认出是哪家。把它的 API 文档链接发给我，我去读接口地址；也可以在上面选一家，或手动填写。")
            }
        }
    }
}

/** Switch the contact being talked to over to this service right away. */
@Composable
internal fun UseForActiveRow(
    setup: ProviderSetup,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = setup.useForActive, onCheckedChange = onChange)
        Text("让「${setup.activeName}」用这个服务商", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun Hint(
    text: String,
    error: Boolean = false,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
