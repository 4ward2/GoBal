@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.services.chat.RootHelper
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryFile
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteContact

/** What the picker shows for a choice that is none of the saved providers' models. */
private fun unlistedLabel(
    current: ModelChoice,
    helper: Boolean,
): Int {
    // An agent on a computer runs whatever model it was started with there.
    if (RemoteContact.of(current) != null) return R.string.contact_model_remote
    return if (helper) R.string.contact_model_on_device else R.string.contact_model_unset
}

/** Every provider's models plus the on-device default, as one dropdown. */
@Composable
internal fun ModelPicker(
    providers: List<ModelProvider>,
    current: ModelChoice,
    onPick: (ModelChoice) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val helper = remember { RootHelper.present() }
    val unset = unlistedLabel(current, helper)
    val options = providers.flatMap { p -> p.models.map { m -> ModelChoice(p.id, m) to "${p.name} · $m" } }
    val label =
        options.firstOrNull { it.first == current }?.second
            ?: providers.firstOrNull { it.id == current.providerId }?.let { "${it.name} · ${current.model}" }
            ?: stringResource(unset)
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            stringResource(R.string.contact_model),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Box {
            Surface(
                onClick = { open = true },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (helper) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.contact_model_on_device)) },
                        onClick = {
                            open = false
                            onPick(ModelChoice.ON_DEVICE)
                        },
                    )
                }
                options.forEach { (choice, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = {
                        open = false
                        onPick(choice)
                    })
                }
            }
        }
        if (providers.isEmpty()) {
            Text(
                stringResource(R.string.contact_model_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
    }
}

@Composable
internal fun MemoryHeader(
    reading: Boolean,
    onAdd: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Text(stringResource(R.string.contact_memory), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.contact_memory_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        FilledTonalButton(
            onClick = onAdd,
            enabled = !reading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            if (reading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.contact_memory_reading))
            } else {
                Icon(Icons.Default.UploadFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.contact_memory_add))
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
internal fun MemoryRow(
    file: MemoryFile,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val detail =
        listOfNotNull(
            stringResource(R.string.contact_memory_chars, file.chars),
            stringResource(R.string.contact_memory_ocr).takeIf { file.ocr },
        ).joinToString(" · ")
    ListItem(
        headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(detail) },
        leadingContent = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = file.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.contact_memory_remove))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onOpen),
    )
}
