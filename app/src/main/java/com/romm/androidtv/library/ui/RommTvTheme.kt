package com.romm.androidtv.library.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Color palette for the native browsing UI (UI_REFACTOR.md section 2),
 * extracted verbatim from romm.games' compiled Tailwind CSS custom
 * properties (`--color-romm-*`, `--color-night-*`, `--color-stage-*`) on
 * 2026-07-28. Deliberately distinct from generic Material dark-theme
 * defaults: dark, purple-accented, console-like.
 */
object RommTvColors {
    val Romm200 = Color(0xFFC8BDF3)
    val Romm300 = Color(0xFFA494EB)
    val Romm400 = Color(0xFF8B74E8)

    /** Primary accent: focus rings, active nav item, highlights. */
    val Romm500 = Color(0xFF7259D1)

    /** Pressed/darker accent. */
    val Romm600 = Color(0xFF5C47AD)

    val Ink = Color(0xFF201A33)

    /** Primary dark background (GitHub-dark-like). */
    val NightHi = Color(0xFF0D1117)

    /** Secondary dark background. */
    val NightLo = Color(0xFF14101F)

    /** Gradient stop A for the sidebar/hero band. */
    val StageHi = Color(0xFF0E0B17)

    /** Gradient stop B for the sidebar/hero band. */
    val StageLo = Color(0xFF241A45)

    val TextPrimary = Color.White
    val TextSecondary = Color(0xFFB6B0C6)
}

private val RommTvColorScheme = darkColorScheme(
    primary = RommTvColors.Romm500,
    onPrimary = Color.White,
    secondary = RommTvColors.Romm400,
    onSecondary = Color.White,
    background = RommTvColors.NightHi,
    onBackground = RommTvColors.TextPrimary,
    surface = RommTvColors.NightLo,
    onSurface = RommTvColors.TextPrimary,
    surfaceVariant = RommTvColors.StageLo,
    onSurfaceVariant = RommTvColors.TextSecondary,
)

@Composable
fun RommTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RommTvColorScheme,
        content = content,
    )
}
