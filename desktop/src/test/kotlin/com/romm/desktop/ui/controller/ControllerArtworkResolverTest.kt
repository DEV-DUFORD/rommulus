package com.romm.desktop.ui.controller

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import com.romm.androidtv.controller.config.ControllerArtwork
import com.romm.androidtv.controller.config.CoreControllerProfiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Pure-logic tests for the desktop [ControllerArtworkResolver]: the shared
 * `ControllerArtwork.resourceName` strings must map to the converted ImageVectors exactly as
 * Android's resolver maps them to drawable resource ids (including the generic-gamepad
 * fallback), and every vector in the shared profile catalog must resolve without falling back.
 */
@DisplayName("Desktop ControllerArtworkResolver — resource-name → ImageVector mapping")
class ControllerArtworkResolverTest {

    /** Desktop controller vectors, including the Linux-only GameCube artwork. */
    private val allResourceNames = listOf(
        "controller_outline_genesis",
        "controller_outline_snes",
        "controller_outline_nes",
        "controller_outline_atari2600",
        "controller_outline_atari7800",
        "controller_outline_ps1",
        "controller_outline_n64",
        "controller_outline_gamecube",
        "controller_outline_gba",
        "controller_outline_gb",
        "controller_outline_tg16",
        "controller_outline_ngp",
        "controller_outline_wswan",
        "controller_outline_lynx",
        ControllerArtworkResolver.GENERIC_GAMEPAD,
        ControllerArtworkResolver.GENERIC_HANDHELD,
    )

    @Nested
    @DisplayName("resource-name mapping")
    inner class NameMapping {

        @Test
        fun `every known resource name resolves to its own vector`() {
            for (name in allResourceNames) {
                assertThat(ControllerArtworkResolver.imageVectorFor(name).name).isEqualTo(name)
            }
        }

        @Test
        fun `the two generic placeholders resolve to themselves`() {
            assertThat(ControllerArtworkResolver.imageVectorFor(ControllerArtworkResolver.GENERIC_GAMEPAD))
                .isSameAs(ControllerOutlineGenericGamepad)
            assertThat(ControllerArtworkResolver.imageVectorFor(ControllerArtworkResolver.GENERIC_HANDHELD))
                .isSameAs(ControllerOutlineGenericHandheld)
        }

        @Test
        fun `unknown and blank names fall back to the generic gamepad`() {
            for (unknown in listOf("controller_outline_future", "", "   ", "SNES")) {
                val vector = ControllerArtworkResolver.imageVectorFor(unknown)
                assertThat(vector).isSameAs(ControllerArtworkResolver.fallback)
                assertThat(vector.name).isEqualTo(ControllerArtworkResolver.GENERIC_GAMEPAD)
            }
        }

        @Test
        fun `the artwork overload delegates to the resource name`() {
            val artwork = ControllerArtwork(
                resourceName = "controller_outline_snes",
                source = "test",
                license = "SIL Open Font License 1.1",
                licenseAssetPath = null,
                viewBoxWidth = 360f,
                viewBoxHeight = 360f,
            )
            assertThat(ControllerArtworkResolver.imageVectorFor(artwork))
                .isSameAs(ControllerArtworkResolver.imageVectorFor("controller_outline_snes"))
        }
    }

    @Nested
    @DisplayName("profile catalog coverage")
    inner class CatalogCoverage {

        @Test
        fun `every shared profile artwork resolves to a non-fallback vector`() {
            assertThat(CoreControllerProfiles.all).isNotEmpty
            for (profile in CoreControllerProfiles.all) {
                val vector = ControllerArtworkResolver.imageVectorFor(profile.artwork)
                assertThat(vector.name).isEqualTo(profile.artwork.resourceName)
                // A profile that silently fell back to the generic gamepad would still render
                // *something* — assert it is the intended silhouette instead.
                if (profile.artwork.resourceName != ControllerArtworkResolver.GENERIC_GAMEPAD) {
                    assertThat(vector).isNotSameAs(ControllerArtworkResolver.fallback)
                }
            }
        }

        @Test
        fun `every profile artwork resource name is a converted desktop vector`() {
            val catalogNames = CoreControllerProfiles.all.map { it.artwork.resourceName }.toSet()
            // The catalog covers the 13 named consoles only; the generic placeholders exist as
            // fallbacks, not as declared profile artwork.
            assertThat(catalogNames).isSubsetOf(allResourceNames)
            assertThat(catalogNames).hasSize(14)
        }
    }

    @Nested
    @DisplayName("converted vector geometry")
    inner class VectorGeometry {

        @Test
        fun `the console silhouettes keep the 360x360 drawable viewport and carry parsed paths`() {
            for (name in allResourceNames - ControllerArtworkResolver.GENERIC_GAMEPAD) {
                val vector = ControllerArtworkResolver.imageVectorFor(name)
                assertThat(vector.viewportWidth).isEqualTo(360f)
                assertThat(vector.viewportHeight).isEqualTo(360f)
                assertHasParsedPaths(vector)
            }
        }

        @Test
        fun `the generic gamepad keeps its 64x64 viewport and five stroked paths`() {
            val vector = ControllerOutlineGenericGamepad
            assertThat(vector.viewportWidth).isEqualTo(64f)
            assertThat(vector.viewportHeight).isEqualTo(64f)
            assertThat(vector.root.filterIsInstance<VectorPath>()).hasSize(5)
            assertHasParsedPaths(vector)
        }

        /** The SVG path data must have actually parsed into geometry (not a blank vector). */
        private fun assertHasParsedPaths(vector: ImageVector) {
            val paths = vector.root.filterIsInstance<VectorPath>()
            assertThat(paths).isNotEmpty
            val nodeCount = paths.sumOf { it.pathData.size }
            assertThat(nodeCount).isGreaterThan(10)
        }
    }
}
