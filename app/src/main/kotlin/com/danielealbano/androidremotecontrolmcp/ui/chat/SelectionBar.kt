@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R

/**
 * Takes the composer's place while several messages are being picked: how many, then leaving
 * them out of the model's context or putting them back.
 */
@Composable
fun SelectionBar(
    count: Int,
    actions: SelectionActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.chat_selected_count, count),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.onExclude, enabled = count > 0) {
                Text(stringResource(R.string.chat_action_exclude))
            }
            TextButton(onClick = actions.onInclude, enabled = count > 0) {
                Text(stringResource(R.string.chat_action_include))
            }
            IconButton(onClick = actions.onCancel) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_select_cancel))
            }
        }
    }
}

/** The tick in front of a row while messages are being picked. */
@Composable
internal fun SelectMark(selected: Boolean) {
    Icon(
        if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
        contentDescription = null,
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(end = 8.dp).size(22.dp),
    )
}
