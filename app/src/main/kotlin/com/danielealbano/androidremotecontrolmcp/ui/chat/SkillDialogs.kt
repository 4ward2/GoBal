@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.services.skill.Skill
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillSource

/** "录制操作": who does the task this time, the AI (from what the user says) or the user by hand. */
@Composable
fun RecordModeDialog(
    onPick: (ai: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("录制操作") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeOption(
                    icon = Icons.Default.RecordVoiceOver,
                    title = "AI 录制",
                    detail = "说出你要手机做的事，AI 做一遍，做成后自动存成技能",
                    onClick = { onPick(true) },
                )
                ModeOption(
                    icon = Icons.Default.PanTool,
                    title = "人工录制",
                    detail = "你自己操作一遍，用悬浮球暂停、停止，眼镜可以识别屏幕上的字或图",
                    onClick = { onPick(false) },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ModeOption(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    ) {
        ListItem(
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(title) },
            supportingContent = { Text(detail) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    }
}

/** "播放操作": the saved skills; tapping one runs it (after asking for its parameters, if any). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaySheet(
    skills: List<Skill>,
    onPick: (Skill) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "播放操作",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (skills.isEmpty()) {
            Text(
                "还没有技能。先用「录制操作」录一个。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(bottom = 24.dp),
        ) {
            items(skills, key = { it.name }) { skill ->
                val origin = if (skill.source == SkillSource.RECORDED) "录制" else "自动归档"
                ListItem(
                    headlineContent = { Text(skill.name) },
                    supportingContent = { Text("${skill.steps.size} 步 · $origin · 运行 ${skill.runCount} 次") },
                    modifier = Modifier.clickable(role = Role.Button) { onPick(skill) },
                )
            }
        }
    }
}

/** The values for a skill's `{{parameters}}`, prefilled with the defaults. */
@Composable
fun SkillParamsDialog(
    skill: Skill,
    onRun: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val values =
        remember(skill) {
            mutableStateMapOf<String, String>().apply { skill.params.forEach { put(it.name, it.defaultValue) } }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(skill.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                skill.params.forEach { p ->
                    OutlinedTextField(
                        value = values[p.name].orEmpty(),
                        onValueChange = { values[p.name] = it },
                        label = { Text(p.name) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            val ready = values.values.all { it.isNotBlank() }
            TextButton(onClick = { onRun(values.toMap()) }, enabled = ready) { Text("播放") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
