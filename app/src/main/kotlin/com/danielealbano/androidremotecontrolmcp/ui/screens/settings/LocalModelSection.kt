@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalServerStatus
import com.danielealbano.androidremotecontrolmcp.services.localmodel.ModelHub
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.LocalModelRow
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.LocalModelUi
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.LocalModelViewModel

/**
 * The "本机模型" part of 设置 → 服务商: open models that run on the phone itself, no network needed
 * once downloaded, and the button that adds one from a Hugging Face / ModelScope link.
 */
internal fun LazyListScope.localModelSection(
    ui: LocalModelUi,
    viewModel: LocalModelViewModel,
    onAdd: () -> Unit,
) {
    item(key = "local-title") { SectionTitle("本机模型") }
    if (ui.rows.isEmpty()) {
        item(key = "local-intro") {
            SectionNote("把开源模型下载到手机上运行：不用联网，也不花钱。手机内存越大，能跑的模型越大。")
        }
    } else {
        item(key = "local-status") { ServerCard(ui, onStop = viewModel::stop) }
        items(ui.rows, key = { "local-${it.spec.id}" }) { row -> ModelCard(row, ui, viewModel) }
    }
    item(key = "local-add") {
        FilledTonalButton(
            onClick = onAdd,
            enabled = ui.supported,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("新增本机模型", style = MaterialTheme.typography.titleMedium)
        }
        if (ui.rows.isNotEmpty()) {
            SectionNote("选它的联系人发消息时会自动唤醒；空闲 5 分钟后释放内存，下次用到再加载。")
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun SectionNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ServerCard(
    ui: LocalModelUi,
    onStop: () -> Unit,
) {
    val status = ui.status
    LocalCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (status is LocalServerStatus.Starting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            }
            Text(
                statusText(ui),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            if (status is LocalServerStatus.Running || status is LocalServerStatus.Starting) {
                TextButton(onClick = onStop) { Text("停止") }
            }
        }
    }
}

private fun statusText(ui: LocalModelUi): String {
    val status = ui.status
    return when {
        !ui.supported -> "这台设备不支持本机模型（需要 64 位 ARM 处理器）"
        status is LocalServerStatus.Starting -> "正在加载「${ui.nameOf(status.modelId)}」…"
        status is LocalServerStatus.Running -> "运行中：${ui.nameOf(status.modelId)}"
        status is LocalServerStatus.Failed -> status.message
        else -> "未运行"
    }
}

@Composable
private fun LocalCard(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun ModelCard(
    row: LocalModelRow,
    ui: LocalModelUi,
    viewModel: LocalModelViewModel,
) {
    val spec = row.spec
    LocalCard {
        Text(spec.name, style = MaterialTheme.typography.titleMedium)
        val from = ModelHub.byName(spec.hub)?.let { "${it.label} · ${spec.repo}" }
        (from ?: spec.summary).takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "下载 ${gb(spec.downloadBytes)} GB · 上下文 ${spec.contextSize} · 运行需约 ${gb(spec.ramBytes)} GB 内存" +
                if (spec.mmproj != null) " · 能看图" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        row.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        ModelActions(row, ui, viewModel)
    }
}

@Composable
private fun ModelActions(
    row: LocalModelRow,
    ui: LocalModelUi,
    viewModel: LocalModelViewModel,
) {
    val spec = row.spec
    val tall = Modifier.heightIn(min = 48.dp)
    when {
        row.progress != null -> {
            OutlinedButton(onClick = { viewModel.cancelDownload(spec) }, modifier = tall) {
                Text("暂停下载（${(row.progress * PERCENT).toInt()}%）")
            }
        }

        !row.installed -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { viewModel.download(spec) }, enabled = ui.supported, modifier = tall) {
                    Text("继续下载")
                }
                TextButton(onClick = { viewModel.delete(spec) }, modifier = tall) { Text("删除") }
            }
        }

        else -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.start(spec) }, modifier = tall) { Text("一键启动") }
                if (ui.activeModel != spec.id) {
                    OutlinedButton(onClick = { viewModel.useForActive(spec) }, modifier = tall) {
                        Text("让「${ui.activeName}」用它")
                    }
                }
                TextButton(onClick = { viewModel.delete(spec) }, modifier = tall) { Text("删除") }
            }
        }
    }
}

internal fun gb(bytes: Long): String = "%.1f".format(bytes / BYTES_PER_GB)

private const val BYTES_PER_GB = 1024.0 * 1024 * 1024
private const val PERCENT = 100
