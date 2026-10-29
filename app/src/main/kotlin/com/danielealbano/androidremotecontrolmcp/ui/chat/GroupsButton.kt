@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R

/** Opens the group chats; true = straight to making a new one. Provided by whoever hosts the chat. */
val LocalGroupOpener = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/** The "+" at the end of the chat header: a new group chat, or the ones there are. */
@Composable
internal fun GroupsButton() {
    val onOpen = LocalGroupOpener.current
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(end = 4.dp)) {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.group_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val pick: (Boolean) -> Unit = { makeNew ->
                open = false
                onOpen(makeNew)
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.group_new)) }, onClick = { pick(true) })
            DropdownMenuItem(text = { Text(stringResource(R.string.group_list_title)) }, onClick = { pick(false) })
        }
    }
}
