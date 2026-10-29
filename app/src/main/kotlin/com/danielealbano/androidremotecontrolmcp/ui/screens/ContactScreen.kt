@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryFile
import com.danielealbano.androidremotecontrolmcp.ui.components.AssistantAvatar
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ContactForm
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ContactUiState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ContactViewModel

/** Which text is being edited on the page; everything else stays read-only until its badge is tapped. */
private enum class Field { NAME, DESCRIPTION, BACKSTORY }

/**
 * The contact page reached from the person icon in the chat header. Reads like a profile:
 * portrait with an edit badge, the name under it, then 简介 and 背景故事 shown as a single line
 * each. Tapping a badge opens the input for that one field; 保存 writes everything back.
 */
@Composable
fun ContactScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Field?>(null) }
    val pickAvatar =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let(viewModel::changeAvatar)
        }
    val pickMemory =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::addMemoryFile)
        }
    LaunchedEffect(ui.notice) {
        ui.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.noticeShown()
        }
    }
    ui.editingMemory?.let { MemoryEditDialog(it, onDone = viewModel::finishMemory) }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item(key = "back") {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
            }
        }
        profile(ui, viewModel, editing, onEditing = { editing = it }) {
            pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        memory(ui, viewModel, onAdd = { pickMemory.launch(arrayOf("*/*")) })
    }
}

/** Portrait, name, 简介, 背景故事, model and the 保存 button. */
private fun LazyListScope.profile(
    ui: ContactUiState,
    viewModel: ContactViewModel,
    editing: Field?,
    onEditing: (Field?) -> Unit,
    onChangeAvatar: () -> Unit,
) {
    item(key = "portrait") {
        Portrait(ui, onChangeAvatar)
        NameLine(ui.form.name, editing == Field.NAME, onToggle = { onEditing(editing.toggled(Field.NAME)) }) { v ->
            viewModel.edit { it.copy(name = v) }
        }
    }
    item(key = "description") {
        EditableField(
            label = stringResource(R.string.contact_description),
            value = ui.form.description,
            editing = editing == Field.DESCRIPTION,
            onToggle = { onEditing(editing.toggled(Field.DESCRIPTION)) },
        ) { v -> viewModel.edit { it.copy(description = v) } }
    }
    item(key = "backstory") {
        EditableField(
            label = stringResource(R.string.contact_backstory),
            value = ui.form.backstory,
            editing = editing == Field.BACKSTORY,
            onToggle = { onEditing(editing.toggled(Field.BACKSTORY)) },
        ) { v -> viewModel.edit { it.copy(backstory = v) } }
    }
    item(key = "model") {
        ModelPicker(ui.providers, ui.form.model, onPick = { choice -> viewModel.edit { it.copy(model = choice) } })
    }
    item(key = "save") {
        Button(
            onClick = {
                onEditing(null)
                viewModel.save()
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).heightIn(min = 52.dp),
        ) {
            Text(stringResource(R.string.chat_rename_save), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The memory files: heading with the add button, then one row per file. */
private fun LazyListScope.memory(
    ui: ContactUiState,
    viewModel: ContactViewModel,
    onAdd: () -> Unit,
) {
    item(key = "memory-title") { MemoryHeader(ui.reading, onAdd) }
    item(key = "memory-write") { WriteMemoryButton(onClick = { viewModel.editMemory(null) }) }
    items(ui.memory, key = { it.id }) { file ->
        MemoryRow(
            file,
            onOpen = { viewModel.editMemory(file.id) },
            onToggle = { viewModel.setMemoryEnabled(file.id, it) },
            onRemove = { viewModel.removeMemoryFile(file.id) },
        )
    }
}

/** Tapping the badge of the field already open closes it; any other badge switches to that field. */
private fun Field?.toggled(field: Field): Field? = if (this == field) null else field

private fun badgeIcon(editing: Boolean): ImageVector = if (editing) Icons.Default.Check else Icons.Default.Edit

/** The big portrait; a character's carries a camera badge that picks a new picture. */
@Composable
private fun Portrait(
    ui: ContactUiState,
    onChangeAvatar: () -> Unit,
) {
    val image = remember(ui.avatar) { ui.avatar?.asImageBitmap() }
    Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
        Box {
            AssistantAvatar(name = ui.form.name, size = PORTRAIT, image = image)
            if (ui.character != null) {
                EditBadge(
                    icon = Icons.Default.PhotoCamera,
                    label = stringResource(R.string.contact_change_avatar),
                    onClick = onChangeAvatar,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
    }
}

/** The name, centred under the portrait, with its badge; the badge turns it into a text box. */
@Composable
private fun NameLine(
    name: String,
    editing: Boolean,
    onToggle: () -> Unit,
    onChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (editing) {
            OutlinedTextField(
                value = name,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                modifier = Modifier.width(NAME_FIELD_WIDTH),
            )
        } else {
            Text(
                name,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = BADGE_SIZE),
            )
        }
        Spacer(Modifier.width(4.dp))
        EditBadge(
            icon = badgeIcon(editing),
            label = stringResource(R.string.contact_name),
            onClick = onToggle,
        )
    }
}

/**
 * A profile line: label, the text's first line, and a badge at its right. Tapping the badge swaps
 * the line for a text box; tapping again (now a tick) folds it back.
 */
@Composable
private fun EditableField(
    label: String,
    value: String,
    editing: Boolean,
    onToggle: () -> Unit,
    onChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            if (editing) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    minLines = 3,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    value
                        .lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.isNotEmpty() }
                        .orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value.isBlank()) MaterialTheme.colorScheme.outline else LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp, bottom = 6.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            EditBadge(icon = badgeIcon(editing), label = label, onClick = onToggle)
        }
    }
}

/** The small round edit / camera / tick button that sits at a field's bottom-right. */
@Composable
private fun EditBadge(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.size(BADGE_SIZE),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(BADGE_ICON))
        }
    }
}

private val PORTRAIT = 112.dp
private val BADGE_SIZE = 32.dp
private val BADGE_ICON = 18.dp
private val NAME_FIELD_WIDTH = 240.dp
