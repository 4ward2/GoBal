@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import kotlinx.coroutines.delay

/**
 * Takes the header's place while searching: the word, "2/5", and arrows to the older and the
 * newer hit. [hits] are the ids of the matching messages, oldest first.
 */
@Composable
fun ChatSearchBar(
    view: ChatViewState,
    hits: List<String>,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // A new word starts at its newest hit, once typing pauses.
    LaunchedEffect(view.activeQuery) {
        delay(TYPING_PAUSE_MS)
        view.hit = (hits.size - 1).coerceAtLeast(0)
        hits.lastOrNull()?.let(view::jumpTo)
    }
    val go: (Boolean) -> Unit = { forward ->
        view.hit = ChatSearch.step(view.hit, hits.size, forward)
        hits.getOrNull(view.hit)?.let(view::jumpTo)
    }
    Row(modifier = modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = view::closeSearch) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.chat_search_close))
        }
        SearchField(view, focus, onNext = { go(false) }, modifier = Modifier.weight(1f))
        Text(
            countLabel(view, hits.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        IconButton(onClick = { go(false) }, enabled = hits.size > 1) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.chat_search_prev))
        }
        IconButton(onClick = { go(true) }, enabled = hits.size > 1) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.chat_search_next))
        }
    }
}

@Composable
private fun countLabel(
    view: ChatViewState,
    count: Int,
): String =
    when {
        view.activeQuery.isEmpty() -> ""
        count == 0 -> stringResource(R.string.chat_search_none)
        else -> stringResource(R.string.chat_search_count, view.hit.coerceIn(0, count - 1) + 1, count)
    }

@Composable
private fun SearchField(
    view: ChatViewState,
    focus: FocusRequester,
    onNext: () -> Unit,
    modifier: Modifier,
) {
    val transparent = Color.Transparent
    TextField(
        value = view.query,
        onValueChange = { view.query = it },
        modifier = modifier.focusRequester(focus),
        placeholder = { Text(stringResource(R.string.chat_search_hint)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onNext() }),
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = transparent,
                unfocusedContainerColor = transparent,
                focusedIndicatorColor = transparent,
                unfocusedIndicatorColor = transparent,
            ),
    )
}

private const val TYPING_PAUSE_MS = 250L
