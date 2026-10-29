@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R

/**
 * A round button over the conversation's bottom corner, shown once the reader is a few messages
 * away from the newest one. [unseen] adds a dot: something arrived while they were reading above.
 */
@Composable
fun JumpToBottomButton(
    visible: Boolean,
    unseen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
        val label = stringResource(if (unseen) R.string.chat_jump_bottom_new else R.string.chat_jump_bottom)
        Box {
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shadowElevation = 3.dp,
                modifier = Modifier.size(BUTTON_SIZE),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = label)
                }
            }
            if (unseen) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(DOT_SIZE)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
    }
}

private val BUTTON_SIZE = 48.dp
private val DOT_SIZE = 12.dp
