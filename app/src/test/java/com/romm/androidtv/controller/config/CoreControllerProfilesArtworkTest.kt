package com.romm.androidtv.controller.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Android-side half of the catalog artwork tests: verifies every profile's artwork
 * [ControllerArtwork.resourceName] resolves to a known drawable via
 * [ControllerArtworkResolver]. The shared catalog itself is tested in :shared:domain
 * (CoreControllerProfilesTest); this class stays in :app because the resolver maps
 * resource names to Android drawable ids.
 */
@DisplayName("CoreControllerProfiles — artwork resource resolution")
class CoreControllerProfilesArtworkTest {

    private val profiles = CoreControllerProfiles.all

    @Test
    fun `every profile artwork resourceName resolves to a known drawable`() {
        // Guards against a wrong/typo'd resourceName shipping unnoticed (spec point 5,
        // non-visual stand-in for a screenshot/golden review until a golden library is added).
        // Every declared resourceName must map to an explicit branch — the two generic
        // placeholders plus the 13 distinct per-family outlines — never silently to the
        // fallback.
        val knownNames = listOf(
            ControllerArtworkResolver.GENERIC_GAMEPAD,
            ControllerArtworkResolver.GENERIC_HANDHELD,
            "controller_outline_genesis",
            "controller_outline_snes",
            "controller_outline_nes",
            "controller_outline_atari2600",
            "controller_outline_atari7800",
            "controller_outline_ps1",
            "controller_outline_n64",
            "controller_outline_gba",
            "controller_outline_gb",
            "controller_outline_tg16",
            "controller_outline_ngp",
            "controller_outline_wswan",
            "controller_outline_lynx",
        )
        val knownIds = knownNames.map(ControllerArtworkResolver::resourceIdFor)
        assertThat(knownIds).allMatch { it > 0 }
        // The 13 console profiles must resolve to 13 distinct outlines (not 2 shared buckets).
        val consoleIds = knownNames.drop(2).map(ControllerArtworkResolver::resourceIdFor)
        assertThat(consoleIds.distinct()).hasSize(consoleIds.size)
        for (profile in profiles) {
            val resourceName = profile.artwork.resourceName
            assertThat(resourceName).`as`("artwork resourceName for %s", profile.coreId).isNotBlank()
            // The resolver must return a valid, non-zero drawable id for every declared name.
            val resolved = ControllerArtworkResolver.resourceIdFor(resourceName)
            assertThat(resolved).`as`("resolved drawable id for %s", profile.coreId).isGreaterThan(0)
            assertThat(resolved).`as`("resolved drawable for %s", profile.coreId).isIn(knownIds)
        }
    }
}
