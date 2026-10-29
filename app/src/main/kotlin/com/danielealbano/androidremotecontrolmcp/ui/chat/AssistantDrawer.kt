@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterCard
import com.danielealbano.androidremotecontrolmcp.ui.components.AssistantAvatar

/** Who can be talked to: the plain assistant plus the imported characters. */
data class DrawerCharacters(
    val assistantName: String,
    val characters: List<CharacterCard>,
    val activeId: String?,
    val avatars: Map<String, ImageBitmap>,
)

/** Character actions in the drawer. */
class CharacterActions(
    val onSelect: (String?) -> Unit,
    val onDelete: (String) -> Unit,
    val onImport: () -> Unit,
)

/** One entry of the character list. */
private data class RosterEntry(
    val name: String,
    val line: String,
    val image: ImageBitmap?,
    val active: Boolean,
)

/**
 * The one menu of the app, opened from the assistant avatar. Borderless and tonal, like the rest
 * of the app: the current persona on top, then the characters as soft cards, conversation actions,
 * and Settings pinned to the bottom.
 */
@Composable
fun AssistantDrawer(
    roster: DrawerCharacters,
    actions: DrawerActions,
    characterActions: CharacterActions,
) {
    val active = roster.characters.firstOrNull { it.id == roster.activeId }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
            PersonaBlock(
                name = active?.name ?: roster.assistantName,
                line =
                    active?.status?.ifBlank { null } ?: active?.summary
                        ?: stringResource(R.string.character_default_summary),
                image = active?.let { roster.avatars[it.id] },
                // The way to the contact's page, for the assistant and the characters alike.
                onClick = actions.onOpenContact,
            )
            SectionLabel(stringResource(R.string.drawer_section_characters))
            CharacterList(roster, characterActions, modifier = Modifier.weight(1f))
            SectionLabel(stringResource(R.string.drawer_section_chat))
            DrawerItem(Icons.Default.AddComment, R.string.chat_menu_new, actions.onNewConversation)
            DrawerItem(Icons.Default.Forum, R.string.chat_menu_conversations, actions.onConversations)
            DrawerItem(Icons.Default.CleaningServices, R.string.chat_menu_clear, actions.onClear)
            DrawerItem(Icons.Default.Settings, R.string.tab_settings, actions.onOpenSettings)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The current persona: portrait, name, and its status line (a character's mood, or a hint). */
@Composable
private fun PersonaBlock(
    name: String,
    line: String,
    image: ImageBitmap?,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 12.dp)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistantAvatar(name = name, size = PERSONA_AVATAR, image = image)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The characters as soft cards, the import button after the last one; the active card is tinted
 * and ticked instead of outlined. Takes whatever height is left so no card is cut in half.
 *
 * The roster is a stack (the repository keeps the last-picked character on top), so with more
 * than one character only the top card is shown until the user expands the rest. Picking a
 * character collapses it again: that card is now on top anyway.
 */
@Composable
private fun CharacterList(
    roster: DrawerCharacters,
    actions: CharacterActions,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val hidden = if (expanded) 0 else (roster.characters.size - 1).coerceAtLeast(0)
    val shown = roster.characters.take(roster.characters.size - hidden)
    LazyColumn(modifier = modifier) {
        item(key = "assistant") {
            val assistant =
                RosterEntry(
                    name = roster.assistantName,
                    line = stringResource(R.string.character_default_summary),
                    image = null,
                    active = roster.activeId == null,
                )
            CharacterCardRow(assistant, onClick = { actions.onSelect(null) }, onDelete = null)
        }
        items(shown, key = { it.id }) { card ->
            val entry =
                RosterEntry(
                    name = card.name,
                    line = card.status.ifBlank { card.summary },
                    image = roster.avatars[card.id],
                    active = card.id == roster.activeId,
                )
            CharacterCardRow(
                entry,
                onClick = {
                    expanded = false
                    actions.onSelect(card.id)
                },
                onDelete = { actions.onDelete(card.id) },
            )
        }
        if (roster.characters.size > 1) {
            item(key = "stack-toggle") {
                StackToggle(hidden = hidden, onToggle = { expanded = !expanded })
            }
        }
        item(key = "import") {
            FilledTonalButton(
                onClick = actions.onImport,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                        .heightIn(min = IMPORT_HEIGHT),
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.character_import), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun CharacterCardRow(
    entry: RosterEntry,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var confirm by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (entry.active) scheme.primaryContainer else scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistantAvatar(name = entry.name, size = ROW_AVATAR, image = entry.image)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    entry.line,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.active) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            if (onDelete != null) {
                IconButton(onClick = { confirm = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.character_delete, entry.name),
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (confirm && onDelete != null) {
        DeleteDialog(entry.name, onConfirm = {
            confirm = false
            onDelete()
        }, onDismiss = { confirm = false })
    }
}

/** "还有 N 个角色" with a chevron; turns into "收起" once the stack is open. */
@Composable
private fun StackToggle(
    hidden: Int,
    onToggle: () -> Unit,
) {
    TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().heightIn(min = TOGGLE_HEIGHT)) {
        Icon(if (hidden > 0) Icons.Default.ExpandMore else Icons.Default.ExpandLess, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(
            if (hidden > 0) {
                stringResource(R.string.character_stack_more, hidden)
            } else {
                stringResource(R.string.character_stack_less)
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun DeleteDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.character_delete, name)) },
        text = { Text(stringResource(R.string.character_delete_confirm)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.character_delete_button)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_rename_cancel)) } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun DrawerItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(stringResource(labelRes), style = MaterialTheme.typography.titleMedium) },
        selected = false,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding).heightIn(min = ITEM_HEIGHT),
    )
}

private val PERSONA_AVATAR = 64.dp
private val ROW_AVATAR = 44.dp
private val IMPORT_HEIGHT = 52.dp
private val TOGGLE_HEIGHT = 48.dp

/** Larger than Material's 56 dp default: these rows are the app's main menu for older users. */
private val ITEM_HEIGHT = 60.dp
