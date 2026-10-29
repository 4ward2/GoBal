@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.ui.components.AssistantAvatar
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.DiscoveredUi
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ImportUiState

/**
 * One-tap import: every character card already on the phone (downloads, chat apps' pictures) is
 * listed with its portrait; no file manager. Cards can also arrive through the share sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterImportSheet(
    state: ImportUiState,
    actions: ImportActions,
) {
    if (!state.open) return
    ModalBottomSheet(onDismissRequest = actions.onClose) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("导入角色卡", style = MaterialTheme.typography.titleLarge)
            when {
                !state.hasPermission -> PermissionAsk(actions.onRequestPermission)
                state.scanning -> Scanning()
                state.found.isEmpty() -> Text("没有在手机里找到角色卡。", style = MaterialTheme.typography.bodyLarge)
                else -> FoundList(state, actions.onImport)
            }
            Text(
                "也可以在浏览器、微信里把角色卡（PNG 或 JSON）「分享」给本应用，会直接导入。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.hasPermission) {
                    OutlinedButton(onClick = actions.onRescan, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("重新查找")
                    }
                }
                TextButton(onClick = actions.onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionAsk(onRequest: () -> Unit) {
    Text("要在手机里找角色卡，需要允许读取图片。", style = MaterialTheme.typography.bodyLarge)
    Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text("允许读取图片", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Scanning() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp), strokeWidth = 3.dp)
        Text("正在查找角色卡…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun FoundList(
    state: ImportUiState,
    onImport: (DiscoveredUi) -> Unit,
) {
    Text("找到 ${state.found.size} 张，点「导入」即可：", style = MaterialTheme.typography.bodyLarge)
    LazyColumn(modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(state.found, key = { it.key }) { item ->
            FoundRow(item, importing = state.importing == item.key, onImport)
        }
    }
}

@Composable
private fun FoundRow(
    item: DiscoveredUi,
    importing: Boolean,
    onImport: (DiscoveredUi) -> Unit,
) {
    val image = remember(item.thumbnail) { item.thumbnail?.asImageBitmap() }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AssistantAvatar(name = item.name, size = 48.dp, image = image)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium)
            Text(
                item.summary.ifEmpty { item.fileName },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (item.alreadyImported) {
            Text("已导入", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Button(onClick = { onImport(item) }, enabled = !importing, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (importing) "导入中…" else "导入")
            }
        }
    }
}

private val LIST_MAX_HEIGHT = 360.dp
