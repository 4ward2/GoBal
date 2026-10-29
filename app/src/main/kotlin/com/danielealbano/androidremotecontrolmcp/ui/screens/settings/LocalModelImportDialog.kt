@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModelSpec
import com.danielealbano.androidremotecontrolmcp.services.localmodel.ModelHub
import com.danielealbano.androidremotecontrolmcp.services.localmodel.RepoFile
import com.danielealbano.androidremotecontrolmcp.services.localmodel.RepoFiles
import com.danielealbano.androidremotecontrolmcp.ui.chat.rememberLocalText
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ModelImportUi
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ModelImportViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.RepoListing
import kotlinx.coroutines.launch

/**
 * 新增本机模型: pick where to download from, paste a link, and the repository's GGUF files are
 * listed by themselves with the suggested one ticked; "下载" adds it and starts fetching.
 */
@Composable
internal fun LocalModelImportDialog(
    ui: ModelImportUi,
    viewModel: ModelImportViewModel,
    onDownload: (LocalModelSpec) -> Unit,
) {
    val plan = ui.plan
    AlertDialog(
        onDismissRequest = viewModel::close,
        title = { Text("新增本机模型") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val hub = ui.hub
                if (hub == null) HubPicker(viewModel::chooseHub) else LinkStep(ui, hub, viewModel)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    plan?.let(onDownload)
                    viewModel.close()
                },
                enabled = plan != null && !ui.tooBig && !ui.readingHeader,
            ) { Text("下载") }
        },
        dismissButton = { TextButton(onClick = viewModel::close) { Text("取消") } },
    )
}

/** First step: the three places models come from, with what each is good for. */
@Composable
private fun HubPicker(onPick: (ModelHub) -> Unit) {
    Text("从哪里下载模型？", style = MaterialTheme.typography.bodyLarge)
    ModelHub.entries.forEach { hub ->
        Surface(
            onClick = { onPick(hub) },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(hub.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    hub.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LinkStep(
    ui: ModelImportUi,
    hub: ModelHub,
    viewModel: ModelImportViewModel,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ModelHub.entries.forEach { h ->
            FilterChip(selected = h == hub, onClick = { viewModel.chooseHub(h) }, label = { Text(h.shortLabel) })
        }
    }
    LinkField(ui.link, viewModel::editLink)
    Text(
        "打开模型页面，复制地址栏的链接粘贴进来；也可以只写「作者/仓库名」。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (ui.link.isBlank()) {
        TextButton(onClick = { viewModel.editLink(hub.exampleRepo) }) { Text("试试智谱 GLM-Edge 4B（2.3 GB）") }
    }
    ListingStatus(ui, viewModel)
}

@Composable
private fun LinkField(
    link: String,
    onChange: (String) -> Unit,
) {
    val field = rememberLocalText(link)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    OutlinedTextField(
        value = field.text,
        onValueChange = {
            field.typed(it, it)
            onChange(it)
        },
        label = { Text("模型链接") },
        placeholder = { Text("https://…") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = {
                scope.launch {
                    clipboard
                        .getClipEntry()
                        ?.clipData
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.text
                        ?.toString()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let(onChange)
                }
            }) { Icon(Icons.Default.ContentPaste, contentDescription = "粘贴链接") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ListingStatus(
    ui: ModelImportUi,
    viewModel: ModelImportViewModel,
) {
    when (val listing = ui.listing) {
        RepoListing.Idle -> {
            Unit
        }

        RepoListing.Loading -> {
            Waiting("正在读取文件列表…")
        }

        is RepoListing.Failed -> {
            Text(listing.message, color = MaterialTheme.colorScheme.error)
        }

        is RepoListing.Ready -> {
            // The pick and what it costs come first: a repository can list two dozen versions.
            PlanSummary(ui)
            RepoFiles.preferredProjector(listing.models.projectors)?.let { projector ->
                VisionRow(projector, ui.vision, viewModel::setVision)
            }
            Text("换一个版本（越大越准，也越慢、越占内存）", style = MaterialTheme.typography.labelLarge)
            val suggested = RepoFiles.preferred(listing.models.weights)
            listing.models.weights.forEach { file ->
                WeightsRow(file, file == ui.weights, file == suggested, onPick = { viewModel.chooseWeights(file) })
            }
        }
    }
}

@Composable
private fun Waiting(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WeightsRow(
    file: RepoFile,
    selected: Boolean,
    suggested: Boolean,
    onPick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(selected = selected, onClick = onPick, role = Role.RadioButton),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            val label = RepoFiles.quantOf(file.path) ?: file.fileName
            Text(if (suggested) "$label · 推荐" else label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${gb(file.bytes)} GB · ${file.fileName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VisionRow(
    projector: RepoFile,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = on, onValueChange = onChange, role = Role.Switch),
    ) {
        Column(Modifier.weight(1f)) {
            Text("同时下载看图模块", style = MaterialTheme.typography.bodyLarge)
            Text(
                "能看懂聊天里的图片，多 ${mb(projector.bytes)} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

/** Size, context and memory of the pick; a model too large for the phone cannot be downloaded. */
@Composable
private fun PlanSummary(ui: ModelImportUi) {
    val plan = ui.plan ?: return
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("将下载「${plan.name}」", style = MaterialTheme.typography.titleMedium)
            if (ui.readingHeader) {
                Waiting("正在读取模型参数…")
            } else {
                val guessed = if (ui.header?.contextLength == null) "（估计）" else ""
                val memory = "运行需约 ${gb(plan.ramBytes)} GB 内存"
                Text(
                    "共 ${gb(plan.downloadBytes)} GB · 上下文 ${plan.contextSize}$guessed · $memory",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (ui.tooBig) {
                Text(
                    "这台手机只有 ${gb(ui.totalMemory)} GB 内存，运行不了。换一个小一点的版本。",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun mb(bytes: Long): Long = bytes / BYTES_PER_MB

private const val BYTES_PER_MB = 1024L * 1024
