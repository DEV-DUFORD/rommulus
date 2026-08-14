package com.romm.androidtv.library.ui

import com.romm.androidtv.library.RommTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// RommTheme (the selectable-theme enum) moved to `:shared:presentation`
// (com.romm.androidtv.library.RommTheme) for the Linux port Phase 4; the
// palette mapping below still resolves it via that import.

/**
 * The complete color palette used by the native browsing UI
 * (UI_REFACTOR.md section 2). Exposed through [RommTvColors] getters that
 * read the currently active palette so every call site recomposes live when
 * the user switches themes.
 */
data class RommPalette(
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
    /** When true, this palette is light and uses a light Material color scheme. */
    val light: Boolean = false,
)

object RommTvPalettes {
    /**
     * "RomMulus": teal palette derived from the rommulus.svg logo
     * (#e4eff1 / #a5c8cf / #3f9099 / #28616a) with matching dark teal-tinted
     * night/stage backgrounds.
     */
    val RomMulus = RommPalette(
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

    /**
     * "RomM": the original purple palette used before theming was added
     * (previously the hardcoded RommTvColors values).
     */
    val RomM = RommPalette(
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

    /**
     * "Crimson": deep red palette with dark blood-red night/stage backgrounds
     * and muted rose accents.
     */
    val Crimson = RommPalette(
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

    /**
     * "Monochrome": grayscale palette with neutral charcoal backgrounds and
     * steel-gray accents.
     */
    val Mono = RommPalette(
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

    /**
     * "Light": light mode palette with soft blue accents on near-white
     * backgrounds. Unlike the dark themes, this uses dark text and a light
     * Material color scheme.
     */
    val Light = RommPalette(
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

    /**
     * "Olive Drab": military olive palette with dark olive-tinted night/stage
     * backgrounds and muted olive accents.
     */
    val Olive = RommPalette(
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

    fun forTheme(theme: RommTheme): RommPalette = when (theme) {
        RommTheme.RomMulus -> RomMulus
        RommTheme.RomM -> RomM
        RommTheme.Crimson -> Crimson
        RommTheme.Mono -> Mono
        RommTheme.Light -> Light
        RommTheme.Olive -> Olive
    }
}

/**
 * Currently active theme, backed by Compose snapshot state so any
 * [RommTvColors] getter read within composition recomposes immediately when
 * the theme changes. Only mutate via [setActiveTheme].
 */
var activeTheme: RommTheme by mutableStateOf(RommTheme.RomMulus)
    private set

/** Switches the active theme, prompting recomposition across the app. */
fun applyTheme(theme: RommTheme) {
    activeTheme = theme
}

private val activePalette: RommPalette
    get() = RommTvPalettes.forTheme(activeTheme)

/**
 * Color palette for the native browsing UI. Every getter reads [activePalette]
 * (which in turn reads the [activeTheme] snapshot state), so all existing call
 * sites — Composable and Modifier builders alike — update live when the theme
 * changes without requiring any call-site changes.
 */
object RommTvColors {
    val Romm200: Color get() = activePalette.romm200
    val Romm300: Color get() = activePalette.romm300
    val Romm400: Color get() = activePalette.romm400

    /** Primary accent: focus rings, active nav item, highlights. */
    val Romm500: Color get() = activePalette.romm500

    /** Pressed/darker accent. */
    val Romm600: Color get() = activePalette.romm600

    val Ink: Color get() = activePalette.ink

    /** Primary dark background. */
    val NightHi: Color get() = activePalette.nightHi

    /** Secondary dark background. */
    val NightLo: Color get() = activePalette.nightLo

    /** Gradient stop A for the sidebar/hero band. */
    val StageHi: Color get() = activePalette.stageHi

    /** Gradient stop B for the sidebar/hero band. */
    val StageLo: Color get() = activePalette.stageLo

    val TextPrimary: Color get() = activePalette.textPrimary
    val TextSecondary: Color get() = activePalette.textSecondary
}

/** Material color scheme derived from [palette]. */
private fun rommTvColorScheme(palette: RommPalette) =
    if (palette.light) {
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

@Composable
fun RommTvTheme(content: @Composable () -> Unit) {
    val theme = activeTheme
    val colorScheme = remember(theme) {
        rommTvColorScheme(RommTvPalettes.forTheme(theme))
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}