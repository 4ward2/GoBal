@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.components.AssistantAvatar
import com.danielealbano.androidremotecontrolmcp.ui.ideas.Idea

internal val BubbleCorner = 20.dp
internal val BubbleTail = 6.dp
internal val MaxBubbleWidth = 300.dp

/** Greeting shown instead of an empty list: big avatar, time-of-day hello, and tappable starters. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmptyConversation(
    speaker: Speaker,
    greeting: String,
    suggestions: List<Idea>,
    onPick: (String) -> Unit,
    body: String = stringResource(R.string.chat_greeting_body),
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AssistantAvatar(name = speaker.name, size = 88.dp, image = speaker.avatar)
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.chat_greeting_title, greeting, speaker.name),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { idea -> SuggestionChip(text = idea.text, onClick = { onPick(idea.text) }) }
        }
    }
}

@Composable
private fun SuggestionChip(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.semantics { contentDescription = text },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/** Avatar on the left, then whatever the assistant side of the row shows. */
@Composable
internal fun AssistantRow(
    speaker: Speaker,
    content: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        AssistantAvatar(name = speaker.name, size = 28.dp, image = speaker.avatar)
        Spacer(Modifier.width(8.dp))
        content()
    }
}

/** Three pulsing dots while the assistant is working on a reply. */
@Composable
fun TypingIndicator(speaker: Speaker) {
    val transition = rememberInfiniteTransition(label = "typing")
    val description = stringResource(R.string.chat_typing, speaker.name)
    AssistantRow(speaker) {
        Surface(
            shape = RoundedCornerShape(BubbleCorner, BubbleCorner, BubbleCorner, BubbleTail),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Row(modifier = Modifier.padding(16.dp, 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(DOT_COUNT) { index ->
                    val alpha by transition.animateFloat(
                        initialValue = DOT_MIN_ALPHA,
                        targetValue = 1f,
                        animationSpec =
                            infiniteRepeatable(
                                animation = tween(DOT_PULSE_MS, delayMillis = index * DOT_STAGGER_MS),
                                repeatMode = RepeatMode.Reverse,
                            ),
                        label = "dot$index",
                    )
                    Box(
                        Modifier
                            .size(8.dp)
                            .alpha(alpha)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                    )
                }
            }
        }
    }
}

/** Inline problem notice; tapping it dismisses it. */
@Composable
fun ErrorNotice(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(14.dp))
                .clickable(role = Role.Button, onClick = onDismiss)
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

private const val DOT_COUNT = 3
private const val DOT_MIN_ALPHA = 0.25f
private const val DOT_PULSE_MS = 450
private const val DOT_STAGGER_MS = 150
