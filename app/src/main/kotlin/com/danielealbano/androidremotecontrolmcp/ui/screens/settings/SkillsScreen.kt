@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.skill.RecorderState
import com.danielealbano.androidremotecontrolmcp.services.skill.Skill
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRunResult
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRunStatus
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillSource
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.SkillsViewModel

/** Settings → 技能. Shows the editor while a recording (or an opened skill) waits to be saved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SkillsViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val current = draft
    if (current != null) {
        SkillEditor(draft = current, viewModel = viewModel, modifier = modifier)
        return
    }
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_skills_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back),
                    )
                }
            },
            windowInsets = WindowInsets(0),
        )
        SkillList(viewModel)
    }
}

@Composable
private fun SkillList(viewModel: SkillsViewModel) {
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val last by viewModel.lastResult.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { RecordCard(viewModel) }
        item { RunStatus(running, last) }
        items(skills, key = { it.name }) { skill ->
            SkillRow(
                skill = skill,
                busy = running != null,
                onRun = { viewModel.run(skill.name) },
                onEdit = { viewModel.edit(skill) },
                onDelete = { confirmDelete = skill.name },
            )
        }
        if (skills.isEmpty()) {
            item {
                Text(
                    "还没有技能。可以自己录一遍；AI 替你做成一件事后，会自动把做法归档成技能，下次同样的话直接重放。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除技能") },
            text = { Text("删除「$name」？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(name)
                    confirmDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun RecordCard(viewModel: SkillsViewModel) {
    val context = LocalContext.current
    val state by viewModel.recorderState.collectAsStateWithLifecycle()
    var refused by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("录一个新技能", style = MaterialTheme.typography.titleMedium)
            Text(
                "点「开始录制」后会回到桌面，像平时一样操作一遍，做完点屏幕顶上的「完成」，再给它起个名字。" +
                    "以后说出这个名字就能自动做。重放时不会替你点付款或提交订单。",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state is RecorderState.Recording) {
                Text("正在录制…", color = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = {
                    refused = !viewModel.startRecording()
                    if (!refused) {
                        context.startActivity(
                            Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_HOME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("开始录制") }
            }
            if (refused) {
                Text("需要先在系统设置里打开本应用的无障碍服务。", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RunStatus(
    running: String?,
    last: SkillRunResult?,
) {
    val text =
        when {
            running != null -> "正在运行「$running」…"
            last == null -> return
            last.status == SkillRunStatus.DONE -> "「${last.skillName}」做完了"
            last.status == SkillRunStatus.HANDED_OVER -> "「${last.skillName}」停在第 ${last.stoppedAt} 步：${last.message}"
            else -> "「${last.skillName}」没做完（第 ${last.stoppedAt ?: "-"} 步）：${last.message}"
        }
    val failed = running == null && last?.status == SkillRunStatus.FAILED
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SkillRow(
    skill: Skill,
    busy: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(skill.name, style = MaterialTheme.typography.titleMedium)
            val origin = if (skill.source == SkillSource.RECORDED) "录制" else "自动归档"
            val params = skill.params.joinToString("、") { "${it.name}=${it.defaultValue}" }
            Text(
                buildString {
                    append("${skill.steps.size} 步 · $origin · 运行 ${skill.runCount} 次")
                    if (params.isNotEmpty()) append("\n参数：$params")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRun, enabled = !busy) { Text("运行") }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}
