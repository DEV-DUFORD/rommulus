package com.romm.androidtv.library.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Selectable app themes. [displayName] is shown in the Settings "Change Theme"
 * chooser; the theme maps to a [RommPalette] in [RommTvPalettes.forTheme].
 */
enum class RommTheme(val displayName: String) {
    RomMulus("RomMulus"),
    RomM("RomM");

    companion object {
        /** Maps a persisted storage id back to a theme, defaulting to [RomMulus]. */
        fun fromStorage(id: String?): RommTheme =
            entries.firstOrNull { it.name == id } ?: RomMulus
    }
}

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

    fun forTheme(theme: RommTheme): RommPalette = when (theme) {
        RommTheme.RomMulus -> RomMulus
        RommTheme.RomM -> RomM
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

/** Material color scheme derived from the active [RommTvColors] palette. */
private val RommTvColorScheme
    get() = darkColorScheme(
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