@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.services.skill.Skill
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillParam
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillStep
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillText
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.SkillsViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.addHandOverAtEnd
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.addVerifyAfter
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.removeStep
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.rename
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.setDescription
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.setParamDefault

/** Name a recording, turn recorded words into parameters, add checkpoints, save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillEditor(
    draft: Skill,
    viewModel: SkillsViewModel,
    modifier: Modifier = Modifier,
) {
    val message by viewModel.message.collectAsStateWithLifecycle()
    var paramFor by remember { mutableStateOf<SkillStep?>(null) }
    var verifyAfter by remember { mutableStateOf<Int?>(null) }
    BackHandler { viewModel.discardDraft() }

    Column(modifier = modifier.fillMaxSize()) {
        EditorTopBar(viewModel)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { NameFields(draft, viewModel) }
            items(draft.params.size) { i -> ParamRow(draft.params[i], viewModel) }
            item { Text("步骤", style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(draft.steps) { i, step ->
                StepRow(
                    index = i,
                    step = step,
                    onParam = { paramFor = step },
                    onVerify = { verifyAfter = i },
                    onRemove = { viewModel.removeStep(i) },
                )
            }
            item {
                TextButton(onClick = viewModel::addHandOverAtEnd) { Text("在最后加一步「交给我」") }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = viewModel::saveDraft, modifier = Modifier.fillMaxWidth()) { Text("保存技能") }
            }
        }
    }
    paramFor?.let { step ->
        ParamDialog(
            literals = SkillText.literals(step),
            onDismiss = { paramFor = null },
            onConfirm = { literal, name ->
                viewModel.makeParam(literal, name)
                paramFor = null
            },
        )
    }
    verifyAfter?.let { index ->
        VerifyDialog(
            onDismiss = { verifyAfter = null },
            onConfirm = { text ->
                viewModel.addVerifyAfter(index, text)
                verifyAfter = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(viewModel: SkillsViewModel) {
    TopAppBar(
        title = { Text("编辑技能") },
        navigationIcon = {
            IconButton(onClick = viewModel::discardDraft) {
                Icon(Icons.Default.Close, contentDescription = "放弃")
            }
        },
        actions = { TextButton(onClick = viewModel::saveDraft) { Text("保存") } },
        windowInsets = WindowInsets(0),
    )
}

@Composable
private fun NameFields(
    draft: Skill,
    viewModel: SkillsViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = viewModel::rename,
            label = { Text("名称（说这句话就会运行）") },
            supportingText = { Text("可以放参数，比如「点{{店名}}外卖」，说「点瑞幸外卖」就换成瑞幸") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.description,
            onValueChange = viewModel::setDescription,
            label = { Text("说明（可不填）") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.params.isNotEmpty()) Text("参数（没说时用的值）", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ParamRow(
    param: SkillParam,
    viewModel: SkillsViewModel,
) {
    OutlinedTextField(
        value = param.defaultValue,
        onValueChange = { viewModel.setParamDefault(param.name, it) },
        label = { Text(param.name) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StepRow(
    index: Int,
    step: SkillStep,
    onParam: () -> Unit,
    onVerify: () -> Unit,
    onRemove: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("${index + 1}. ${SkillText.describe(step)}", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (SkillText.literals(step).isNotEmpty()) TextButton(onClick = onParam) { Text("设为参数") }
                TextButton(onClick = onVerify) { Text("之后核对") }
                TextButton(onClick = onRemove) { Text("删掉") }
            }
        }
    }
}

@Composable
private fun ParamDialog(
    literals: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (literal: String, name: String) -> Unit,
) {
    var chosen by remember { mutableStateOf(literals.firstOrNull().orEmpty()) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设为参数") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("这个技能里所有的这段文字都会换成参数，运行时可以换成别的。")
                literals.forEach { literal ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth().selectable(
                                selected = chosen == literal,
                                role = Role.RadioButton,
                                onClick = { chosen = literal },
                            ),
                    ) {
                        RadioButton(selected = chosen == literal, onClick = null)
                        Text(literal)
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("参数名，比如「店名」") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(chosen, name.trim()) },
                enabled = chosen.isNotBlank() && name.isNotBlank(),
            ) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun VerifyDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("之后核对") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("这一步做完后，屏幕上必须出现这段文字才继续（看不到时会识别截图）。可以写参数，比如「{{店名}}」。")
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
