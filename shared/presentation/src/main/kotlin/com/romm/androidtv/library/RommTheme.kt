package com.romm.androidtv.library

/**
 * Selectable app themes. [displayName] is shown in the Settings "Change Theme"
 * chooser; each theme maps to a color palette on the platform side (see the
 * app's `RommTvPalettes.forTheme`).
 */
enum class RommTheme(val displayName: String) {
    RomMulus("RomMulus"),
    RomM("RomM"),
    Crimson("Crimson"),
    Mono("Monochrome"),
    Light("Light"),
    Olive("Olive Drab");

    companion object {
        /** Maps a persisted storage id back to a theme, defaulting to [RomMulus]. */
        fun fromStorage(id: String?): RommTheme =
            entries.firstOrNull { it.name == id } ?: RomMulus
    }
}
