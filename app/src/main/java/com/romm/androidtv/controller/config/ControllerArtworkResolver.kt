package com.romm.androidtv.controller.config

import com.romm.androidtv.R

/**
 * Resolves a profile's artwork [ControllerArtwork.resourceName] to a concrete Android
 * drawable resource id.
 *
 * Compile-time `when` mapping (no runtime `getIdentifier` lookup) so a typo or a missing
 * placeholder fails compilation rather than rendering a blank panel. During the
 * "Required asset spike" every real resourceName maps to one of the generic ORIGINAL
 * placeholder drawables ([GENERIC_GAMEPAD] / [GENERIC_HANDHELD]). When the real
 * licensed assets are imported, only these branches change — no call-site or architecture
 * changes are required.
 *
 * Mapping policy:
 * - **Gamepad-like consoles** (rounded twin-stick controller): Genesis, SNES, NES,
 *   Atari 2600, Atari 7800 (a home console with a joystick), PS1, N64.
 * - **Handheld-like consoles** (vertical silhouette): GBA, Game Boy/Color,
 *   TurboGrafx-16 (sourced from the Pineapple handheld collection), Neo Geo Pocket,
 *   WonderSwan, Atari Lynx.
 */
object ControllerArtworkResolver {

    const val GENERIC_GAMEPAD = "controller_outline_generic_gamepad"
    const val GENERIC_HANDHELD = "controller_outline_generic_handheld"

    /**
     * Default fallback used for unknown/blank resource names so a future profile with
     * a yet-to-be-imported asset still renders a (generic) illustration instead of nothing.
     */
    val fallbackResourceId: Int = R.drawable.controller_outline_generic_gamepad

    fun resourceIdFor(resourceName: String): Int = when (resourceName) {
        // --- Generic placeholder drawables resolve to themselves ---
        GENERIC_GAMEPAD -> R.drawable.controller_outline_generic_gamepad
        GENERIC_HANDHELD -> R.drawable.controller_outline_generic_handheld
        // --- Gamepad-like consoles ---
        "controller_outline_genesis" -> R.drawable.controller_outline_generic_gamepad
        "controller_outline_snes" -> R.drawable.controller_outline_generic_gamepad
        "controller_outline_nes" -> R.drawable.controller_outline_generic_gamepad
        "controller_outline_atari2600" -> R.drawable.controller_outline_generic_gamepad
        "controller_outline_atari7800" -> R.drawable.controller_outline_generic_gamepad
        "controller_outline_ps1" -> R.drawable.controller_outline_generic_gamepad
        "controller_outline_n64" -> R.drawable.controller_outline_generic_gamepad
        // --- Handheld-like consoles ---
        "controller_outline_gba" -> R.drawable.controller_outline_generic_handheld
        "controller_outline_gb" -> R.drawable.controller_outline_generic_handheld
        "controller_outline_tg16" -> R.drawable.controller_outline_generic_handheld
        "controller_outline_ngp" -> R.drawable.controller_outline_generic_handheld
        "controller_outline_wswan" -> R.drawable.controller_outline_generic_handheld
        "controller_outline_lynx" -> R.drawable.controller_outline_generic_handheld
        // --- Fallback for any future/unknown resource ---
        else -> fallbackResourceId
    }

    /** Convenience resolver for a [ControllerArtwork] instance. */
    fun resourceIdFor(artwork: ControllerArtwork): Int = resourceIdFor(artwork.resourceName)
}
