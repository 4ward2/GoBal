@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.danielealbano.androidremotecontrolmcp.data.model.Appearance
import com.danielealbano.androidremotecontrolmcp.utils.ApiLevel

@Composable
fun AndroidRemoteControlMcpTheme(
    appearance: Appearance = Appearance(),
    darkTheme: Boolean = appearance.theme.isDark(isSystemInDarkTheme()),
    // Off by default: the assistant keeps one recognisable brand palette instead of the wallpaper colours.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && ApiLevel.atLeast(Build.VERSION_CODES.S) -> {
                val context = LocalContext.current
                if (darkTheme) {
                    dynamicDarkColorScheme(context)
                } else {
                    dynamicLightColorScheme(context)
                }
            }

            darkTheme -> {
                DarkColorScheme
            }

            else -> {
                LightColorScheme
            }
        }

    // The chosen text size multiplies the phone's own font scale, so every sp in the app follows it.
    val density = LocalDensity.current
    val scaled = Density(density.density, density.fontScale * appearance.textSize.scale)
    CompositionLocalProvider(LocalDensity provides scaled) {
        MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
    }
}
