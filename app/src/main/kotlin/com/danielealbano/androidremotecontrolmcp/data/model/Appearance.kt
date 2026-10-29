package com.danielealbano.androidremotecontrolmcp.data.model

private const val LARGE_SCALE = 1.15f
private const val EXTRA_LARGE_SCALE = 1.3f

/** Light or dark, or whatever the phone is set to. */
enum class ThemeMode(
    val code: String,
) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    /** Whether the dark palette is used, given what the phone itself is set to. */
    fun isDark(systemDark: Boolean): Boolean =
        when (this) {
            SYSTEM -> systemDark
            LIGHT -> false
            DARK -> true
        }

    companion object {
        fun of(code: String?): ThemeMode = entries.firstOrNull { it.code == code } ?: SYSTEM
    }
}

/** How large the app's text is, on top of the phone's own font size setting. */
enum class TextSize(
    val code: String,
    val scale: Float,
) {
    STANDARD("standard", 1f),
    LARGE("large", LARGE_SCALE),
    EXTRA_LARGE("extra_large", EXTRA_LARGE_SCALE),
    ;

    companion object {
        fun of(code: String?): TextSize = entries.firstOrNull { it.code == code } ?: STANDARD
    }
}

/** How the app looks: its palette and its text size. */
data class Appearance(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val textSize: TextSize = TextSize.STANDARD,
)
