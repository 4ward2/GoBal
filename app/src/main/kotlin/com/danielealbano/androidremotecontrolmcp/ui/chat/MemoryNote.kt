@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R

/** Which memories an answer was given, and how many more were in reach but did not fit. */
data class MemoryNote(
    val ids: List<String>,
    val left: Int = 0,
)

/** Opens the memory library on the entries with these ids; the chat's host provides it. */
val LocalMemoryOpener = staticCompositionLocalOf<(List<String>) -> Unit> { {} }

/**
 * The line under an answer saying what it remembered. A wrong answer often comes from a wrong
 * memory: one tap from here leads to exactly the entries that went into it.
 */
@Composable
fun MemoryLine(note: MemoryNote) {
    val open = LocalMemoryOpener.current
    val words =
        if (note.left == 0) {
            stringResource(R.string.chat_memory_used, note.ids.size)
        } else {
            stringResource(R.string.chat_memory_used_some, note.ids.size, note.left)
        }
    Text(
        words,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { open(note.ids) }.padding(top = 4.dp, bottom = 2.dp),
    )
}
