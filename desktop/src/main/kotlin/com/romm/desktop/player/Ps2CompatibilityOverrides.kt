package com.romm.desktop.player

import java.util.Locale

/**
 * Conservative renderer overrides for PS2 games with documented severe
 * hardware-renderer correctness failures. Entries require game-specific
 * evidence; this is not inferred from general compatibility ratings.
 */
object Ps2CompatibilityOverrides {
    private val softwareRendererTitles = setOf(
        // PCSX2 #5157: all hardware backends corrupt rendering; software is correct.
        "splashdown",
    )

    fun rendererFor(
        platformSlug: String,
        title: String,
        fileName: String = "",
    ): RendererOverride? {
        if (platformSlug != "ps2") return null
        val candidates = sequenceOf(title, fileName)
            .map(::normalizeTitle)
        return RendererOverride.SOFTWARE_HW.takeIf {
            candidates.any { candidate -> candidate in softwareRendererTitles }
        }
    }

    private fun normalizeTitle(value: String): String =
        value.substringBefore(" (").trim().lowercase(Locale.ROOT)
}
