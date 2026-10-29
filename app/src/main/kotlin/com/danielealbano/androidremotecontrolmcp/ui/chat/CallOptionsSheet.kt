@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R

/** Slides up from the bottom when the phone icon is tapped: voice call or video call. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallOptionsSheet(
    onPick: (video: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            CallOption(Icons.Default.Call, R.string.call_voice) { onPick(false) }
            CallOption(Icons.Default.Videocam, R.string.call_video) { onPick(true) }
        }
    }
}

@Composable
private fun CallOption(
    icon: ImageVector,
    label: Int,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(label), style = MaterialTheme.typography.titleMedium) },
            leadingContent = { Icon(icon, contentDescription = null) },
            modifier = Modifier.heightIn(min = ROW_HEIGHT),
        )
    }
}

private val ROW_HEIGHT = 56.dp
