package com.romm.androidtv.controller.config

import com.romm.androidtv.R

/**
 * Resolves a profile's artwork [ControllerArtwork.resourceName] to a concrete Android
 * drawable resource id.
 *
 * Compile-time `when` mapping (no runtime `getIdentifier` lookup) so missing resources
 * fail compilation rather than rendering a blank panel.
 *
 * Mapping policy:
 * - The two generic placeholders resolve to themselves.
 * - The three families covered by Controllercons resolve to the authoritative 2.1
 *   outline vectors.
 * - The remaining seven families resolve to their artist-provided silhouette vectors.
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
        // --- Distinct per-family outlines (one per approved console) ---
        "controller_outline_genesis" -> R.drawable.controller_outline_genesis
        "controller_outline_snes" -> R.drawable.controller_outline_snes
        "controller_outline_nes" -> R.drawable.controller_outline_nes
        "controller_outline_atari2600" -> R.drawable.controller_outline_atari2600
        "controller_outline_atari7800" -> R.drawable.controller_outline_atari7800
        "controller_outline_ps1" -> R.drawable.controller_outline_ps1
        "controller_outline_n64" -> R.drawable.controller_outline_n64
        "controller_outline_gba" -> R.drawable.controller_outline_gba
        "controller_outline_gb" -> R.drawable.controller_outline_gb
        "controller_outline_tg16" -> R.drawable.controller_outline_tg16
        "controller_outline_ngp" -> R.drawable.controller_outline_ngp
        "controller_outline_wswan" -> R.drawable.controller_outline_wswan
        "controller_outline_lynx" -> R.drawable.controller_outline_lynx
        // --- Fallback for any future/unknown resource ---
        else -> fallbackResourceId
    }

    /** Convenience resolver for a [ControllerArtwork] instance. */
    fun resourceIdFor(artwork: ControllerArtwork): Int = resourceIdFor(artwork.resourceName)
}
