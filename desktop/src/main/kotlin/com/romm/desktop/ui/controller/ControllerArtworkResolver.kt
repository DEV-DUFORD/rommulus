package com.romm.desktop.ui.controller

import androidx.compose.ui.graphics.vector.ImageVector
import com.romm.androidtv.controller.config.ControllerArtwork

/**
 * Desktop port of Android's `ControllerArtworkResolver` (app/.../controller/config): maps a
 * profile's artwork [ControllerArtwork.resourceName] to a concrete Compose [ImageVector].
 *
 * Compile-time `when` mapping (no runtime lookup) so a renamed resource fails compilation
 * rather than rendering a blank panel.
 *
 * Mapping policy (mirrors the Android resolver):
 * - The two generic placeholders resolve to themselves.
 * - The fourteen per-console silhouettes resolve to their converted vectors.
 * - Any unknown/blank resource name falls back to the generic gamepad so a future profile
 *   with a yet-to-be-imported asset still renders an illustration instead of nothing.
 */
object ControllerArtworkResolver {

    const val GENERIC_GAMEPAD = "controller_outline_generic_gamepad"
    const val GENERIC_HANDHELD = "controller_outline_generic_handheld"

    /** Default fallback for unknown/blank resource names (Android parity). */
    val fallback: ImageVector get() = ControllerOutlineGenericGamepad

    fun imageVectorFor(resourceName: String): ImageVector = when (resourceName) {
        // --- Generic placeholder vectors resolve to themselves ---
        GENERIC_GAMEPAD -> ControllerOutlineGenericGamepad
        GENERIC_HANDHELD -> ControllerOutlineGenericHandheld
        // --- Distinct per-console silhouettes (one per approved core) ---
        "controller_outline_genesis" -> ControllerOutlineGenesis
        "controller_outline_snes" -> ControllerOutlineSnes
        "controller_outline_nes" -> ControllerOutlineNes
        "controller_outline_atari2600" -> ControllerOutlineAtari2600
        "controller_outline_atari7800" -> ControllerOutlineAtari7800
        "controller_outline_ps1" -> ControllerOutlinePs1
        "controller_outline_n64" -> ControllerOutlineN64
        "controller_outline_gamecube" -> ControllerOutlineGamecube
        "controller_outline_gba" -> ControllerOutlineGba
        "controller_outline_gb" -> ControllerOutlineGb
        "controller_outline_tg16" -> ControllerOutlineTg16
        "controller_outline_ngp" -> ControllerOutlineNgp
        "controller_outline_wswan" -> ControllerOutlineWswan
        "controller_outline_lynx" -> ControllerOutlineLynx
        // --- Fallback for any future/unknown resource ---
        else -> fallback
    }

    /** Convenience resolver for a [ControllerArtwork] instance. */
    fun imageVectorFor(artwork: ControllerArtwork): ImageVector =
        imageVectorFor(artwork.resourceName)
}
