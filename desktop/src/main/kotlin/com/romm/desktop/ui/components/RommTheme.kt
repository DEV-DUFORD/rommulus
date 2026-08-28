package com.romm.desktop.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.romm.androidtv.library.RommTheme

// ---------------------------------------------------------------------------
// Palette data (ported from Android RommTvPalettes)
// ---------------------------------------------------------------------------

/**
 * Color palette for one theme variant. Mirrors the structure of the
 * Android [RommPalette] but is defined here so the desktop module is
 * self-contained (no dependency on the Android-only RommTvPalettes).
 */
data class RommDesktopPalette(
    val romm200: Color,
    val romm300: Color,
    val romm400: Color,
    val romm500: Color,
    val romm600: Color,
    val ink: Color,
    val nightHi: Color,
    val nightLo: Color,
    val stageHi: Color,
    val stageLo: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val light: Boolean = false,
)

/** All six theme palettes, matching the Android [RommTvPalettes] values exactly. */
object RommDesktopPalettes {
    val RomMulus = RommDesktopPalette(
        romm200 = Color(0xFFCDE2E5),
        romm300 = Color(0xFFA5C8CF),
        romm400 = Color(0xFF6FA9B3),
        romm500 = Color(0xFF3F9099),
        romm600 = Color(0xFF28616A),
        ink = Color(0xFF102427),
        nightHi = Color(0xFF0A1719),
        nightLo = Color(0xFF0F1F21),
        stageHi = Color(0xFF0B1A1C),
        stageLo = Color(0xFF1C4449),
        textPrimary = Color.White,
        textSecondary = Color(0xFFB3C6CA),
    )

    val RomM = RommDesktopPalette(
        romm200 = Color(0xFFC8BDF3),
        romm300 = Color(0xFFA494EB),
        romm400 = Color(0xFF8B74E8),
        romm500 = Color(0xFF7259D1),
        romm600 = Color(0xFF5C47AD),
        ink = Color(0xFF201A33),
        nightHi = Color(0xFF0D1117),
        nightLo = Color(0xFF14101F),
        stageHi = Color(0xFF0E0B17),
        stageLo = Color(0xFF241A45),
        textPrimary = Color.White,
        textSecondary = Color(0xFFB6B0C6),
    )

    val Crimson = RommDesktopPalette(
        romm200 = Color(0xFFF0C4C4),
        romm300 = Color(0xFFE39A9A),
        romm400 = Color(0xFFD16969),
        romm500 = Color(0xFFB23A3A),
        romm600 = Color(0xFF8E2C2C),
        ink = Color(0xFF2B1111),
        nightHi = Color(0xFF1A0A0A),
        nightLo = Color(0xFF221010),
        stageHi = Color(0xFF1B0B0B),
        stageLo = Color(0xFF4A1C1C),
        textPrimary = Color.White,
        textSecondary = Color(0xFFD9B3B3),
    )

    val Mono = RommDesktopPalette(
        romm200 = Color(0xFFE0E0E0),
        romm300 = Color(0xFFBDBDBD),
        romm400 = Color(0xFF9E9E9E),
        romm500 = Color(0xFF757575),
        romm600 = Color(0xFF616161),
        ink = Color(0xFF111111),
        nightHi = Color(0xFF0A0A0A),
        nightLo = Color(0xFF141414),
        stageHi = Color(0xFF0B0B0B),
        stageLo = Color(0xFF2E2E2E),
        textPrimary = Color.White,
        textSecondary = Color(0xFFBDBDBD),
    )

    val Light = RommDesktopPalette(
        romm200 = Color(0xFFD6E6F2),
        romm300 = Color(0xFFAFCEE3),
        romm400 = Color(0xFF5E9CC9),
        romm500 = Color(0xFF2F7DB4),
        romm600 = Color(0xFF1F5F8C),
        ink = Color(0xFF0F2434),
        nightHi = Color(0xFFF4F6F8),
        nightLo = Color(0xFFE6EBF0),
        stageHi = Color(0xFFFDFDFE),
        stageLo = Color(0xFFC9D9E6),
        textPrimary = Color(0xFF1A2733),
        textSecondary = Color(0xFF5A6B7A),
        light = true,
    )

    val Olive = RommDesktopPalette(
        romm200 = Color(0xFFD9D6B3),
        romm300 = Color(0xFFBDB97F),
        romm400 = Color(0xFFA09A55),
        romm500 = Color(0xFF7C7836),
        romm600 = Color(0xFF5E5B29),
        ink = Color(0xFF1E1E10),
        nightHi = Color(0xFF0F100A),
        nightLo = Color(0xFF181910),
        stageHi = Color(0xFF101109),
        stageLo = Color(0xFF34351B),
        textPrimary = Color.White,
        textSecondary = Color(0xFFB8B89A),
    )

    fun forTheme(theme: RommTheme): RommDesktopPalette = when (theme) {
        RommTheme.RomMulus -> RomMulus
        RommTheme.RomM -> RomM
        RommTheme.Crimson -> Crimson
        RommTheme.Mono -> Mono
        RommTheme.Light -> Light
        RommTheme.Olive -> Olive
    }
}

// ---------------------------------------------------------------------------
// CompositionLocal for runtime color access
// ---------------------------------------------------------------------------

/**
 * Provides access to the current theme's color palette from anywhere in
 * the composition tree. Set via [RommulusTheme].
 */
val LocalRommulusColors = compositionLocalOf<RommDesktopPalette> {
    error("RommulusTheme not set. Wrap your content in RommulusTheme.")
}

/**
 * Provides access to the active [RommTheme] from anywhere in the composition tree. Set via
 * [RommulusTheme]. Compose Desktop `Dialog`/`Window` content is a SEPARATE composition, so
 * locals do not propagate into dialog windows — read this local in the outer composition and
 * pass the captured value into an explicit `RommulusTheme(theme = ...)` wrap around dialog
 * content (see [com.romm.desktop.ui.screens.detail.LicensesDialog]).
 */
val LocalRommulusTheme = compositionLocalOf<RommTheme> {
    error("RommulusTheme not set. Wrap your content in RommulusTheme.")
}

// ---------------------------------------------------------------------------
// Theme application
// ---------------------------------------------------------------------------

/**
 * Applies the RomM theme system to a Compose Desktop composition.
 *
 * Maps the [RommTheme] enum to a Material3 color scheme derived from the
 * matching palette (dark scheme for all themes except [RommTheme.Light],
 * which uses a light scheme). The palette is exposed via [LocalRommulusColors]
 * for use by all desktop components.
 *
 * Example:
 * ```
 * RommulusTheme(theme = RommTheme.RomMulus) {
 *     MyScreen()
 * }
 * ```
 */
@Composable
fun RommulusTheme(
    theme: RommTheme,
    content: @Composable () -> Unit,
) {
    val palette = RommDesktopPalettes.forTheme(theme)
    val colorScheme = if (palette.light) {
        lightColorScheme(
            primary = palette.romm500,
            onPrimary = Color.White,
            secondary = palette.romm400,
            onSecondary = Color.White,
            background = palette.nightHi,
            onBackground = palette.textPrimary,
            surface = palette.nightLo,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.stageLo,
            onSurfaceVariant = palette.textSecondary,
        )
    } else {
        darkColorScheme(
            primary = palette.romm500,
            onPrimary = Color.White,
            secondary = palette.romm400,
            onSecondary = Color.White,
            background = palette.nightHi,
            onBackground = palette.textPrimary,
            surface = palette.nightLo,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.stageLo,
            onSurfaceVariant = palette.textSecondary,
        )
    }

    CompositionLocalProvider(
        LocalRommulusColors provides palette,
        LocalRommulusTheme provides theme,
    ) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
