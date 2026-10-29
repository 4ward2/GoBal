@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danielealbano.androidremotecontrolmcp.ui.theme.AccentGradientEnd
import com.danielealbano.androidremotecontrolmcp.ui.theme.AccentGradientStart

/**
 * The assistant's face: the character's portrait when there is one, otherwise a brand-gradient
 * circle with the first character of the name. Decorative — callers put the name in the
 * surrounding semantics.
 */
@Composable
fun AssistantAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    image: ImageBitmap? = null,
) {
    val initial = name.trim().firstOrNull()?.toString() ?: "?"
    // One avatar per assistant row in the chat list: keep the brush and style stable across
    // recompositions so scrolling does not re-allocate and re-draw them for every row.
    val style =
        remember(size) {
            TextStyle(color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * INITIAL_SCALE).sp)
        }
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(AvatarBrush),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(text = initial, style = style)
        }
    }
}

private val AvatarBrush = Brush.linearGradient(listOf(AccentGradientStart, AccentGradientEnd))

private const val INITIAL_SCALE = 0.42f
