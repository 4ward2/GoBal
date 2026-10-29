@file:Suppress("MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand accent: blue -> violet, used as a gradient for the assistant avatar and highlights.
val AccentGradientStart = Color(0xFF4F7CFF)
val AccentGradientEnd = Color(0xFFA06BFF)

// Presence dot for a contact whose model API refused us ("拉黑"); the other states use scheme colours.
val PresenceBlocked = Color(0xFF8C1D2F)

/** The blue light round the composer while an operation is recorded. */
val RecordGlowBlue = Color(0xFF2F7BFF)
val RecordGlowSky = Color(0xFF6CC4FF)
val RecordGlowViolet = Color(0xFF7C5CFF)

/** The extra blues of the voice-wake ripple during an AI recording. */
val RecordWaveLight = Color(0xFF4FA3FF)
val RecordWaveDeep = Color(0xFF1E5BFF)
val RecordWaveIndigo = Color(0xFF3A6BFF)

// Context-window ring between 75 % and 95 %: amber, readable on both themes.
val ContextWarn = Color(0xFFE0A100)

/** Behind a search hit inside a message: amber, see-through enough for any bubble colour. */
val SearchMark = Color(0x80FFC400)

// Light theme
val PrimaryLight = Color(0xFF3D5AFE)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFE3E8FF)
val OnPrimaryContainerLight = Color(0xFF0A1A66)
val SecondaryLight = Color(0xFF6B5BD6)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFECE8FF)
val OnSecondaryContainerLight = Color(0xFF221A5C)
val TertiaryLight = Color(0xFF0B8A89)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFD2F4F2)
val OnTertiaryContainerLight = Color(0xFF00302F)
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)
val SurfaceLight = Color(0xFFF7F8FA)
val OnSurfaceLight = Color(0xFF111318)
val SurfaceVariantLight = Color(0xFFE8EAEF)
val OnSurfaceVariantLight = Color(0xFF5B5F6B)
val OutlineLight = Color(0xFF8A8F9C)
val OutlineVariantLight = Color(0xFFD9DCE3)
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF3F4F7)
val SurfaceContainerLight = Color(0xFFEEF0F4)
val SurfaceContainerHighLight = Color(0xFFE8EAEF)
val SurfaceContainerHighestLight = Color(0xFFE2E4EA)

// Dark theme
val PrimaryDark = Color(0xFF8FA3FF)
val OnPrimaryDark = Color(0xFF0B1A6B)
val PrimaryContainerDark = Color(0xFF2A3BAE)
val OnPrimaryContainerDark = Color(0xFFE3E8FF)
val SecondaryDark = Color(0xFFB9ADFF)
val OnSecondaryDark = Color(0xFF2A2170)
val SecondaryContainerDark = Color(0xFF3E3480)
val OnSecondaryContainerDark = Color(0xFFECE8FF)
val TertiaryDark = Color(0xFF6FD8D6)
val OnTertiaryDark = Color(0xFF003736)
val TertiaryContainerDark = Color(0xFF00504F)
val OnTertiaryContainerDark = Color(0xFFD2F4F2)
val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
val SurfaceDark = Color(0xFF0E0F13)
val OnSurfaceDark = Color(0xFFECEDF2)
val SurfaceVariantDark = Color(0xFF1E2027)
val OnSurfaceVariantDark = Color(0xFFA5A9B5)
val OutlineDark = Color(0xFF5E6270)
val OutlineVariantDark = Color(0xFF2E313A)
val SurfaceContainerLowestDark = Color(0xFF0A0B0E)
val SurfaceContainerLowDark = Color(0xFF131419)
val SurfaceContainerDark = Color(0xFF17181E)
val SurfaceContainerHighDark = Color(0xFF1E2027)
val SurfaceContainerHighestDark = Color(0xFF262830)

val LightColorScheme =
    lightColorScheme(
        primary = PrimaryLight,
        onPrimary = OnPrimaryLight,
        primaryContainer = PrimaryContainerLight,
        onPrimaryContainer = OnPrimaryContainerLight,
        secondary = SecondaryLight,
        onSecondary = OnSecondaryLight,
        secondaryContainer = SecondaryContainerLight,
        onSecondaryContainer = OnSecondaryContainerLight,
        tertiary = TertiaryLight,
        onTertiary = OnTertiaryLight,
        tertiaryContainer = TertiaryContainerLight,
        onTertiaryContainer = OnTertiaryContainerLight,
        error = ErrorLight,
        onError = OnErrorLight,
        errorContainer = ErrorContainerLight,
        onErrorContainer = OnErrorContainerLight,
        background = SurfaceLight,
        onBackground = OnSurfaceLight,
        surface = SurfaceLight,
        onSurface = OnSurfaceLight,
        surfaceVariant = SurfaceVariantLight,
        onSurfaceVariant = OnSurfaceVariantLight,
        outline = OutlineLight,
        outlineVariant = OutlineVariantLight,
        surfaceContainerLowest = SurfaceContainerLowestLight,
        surfaceContainerLow = SurfaceContainerLowLight,
        surfaceContainer = SurfaceContainerLight,
        surfaceContainerHigh = SurfaceContainerHighLight,
        surfaceContainerHighest = SurfaceContainerHighestLight,
    )

val DarkColorScheme =
    darkColorScheme(
        primary = PrimaryDark,
        onPrimary = OnPrimaryDark,
        primaryContainer = PrimaryContainerDark,
        onPrimaryContainer = OnPrimaryContainerDark,
        secondary = SecondaryDark,
        onSecondary = OnSecondaryDark,
        secondaryContainer = SecondaryContainerDark,
        onSecondaryContainer = OnSecondaryContainerDark,
        tertiary = TertiaryDark,
        onTertiary = OnTertiaryDark,
        tertiaryContainer = TertiaryContainerDark,
        onTertiaryContainer = OnTertiaryContainerDark,
        error = ErrorDark,
        onError = OnErrorDark,
        errorContainer = ErrorContainerDark,
        onErrorContainer = OnErrorContainerDark,
        background = SurfaceDark,
        onBackground = OnSurfaceDark,
        surface = SurfaceDark,
        onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = OnSurfaceVariantDark,
        outline = OutlineDark,
        outlineVariant = OutlineVariantDark,
        surfaceContainerLowest = SurfaceContainerLowestDark,
        surfaceContainerLow = SurfaceContainerLowDark,
        surfaceContainer = SurfaceContainerDark,
        surfaceContainerHigh = SurfaceContainerHighDark,
        surfaceContainerHighest = SurfaceContainerHighestDark,
    )
