package com.romm.desktop.ui.image

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the bundled image assets (window icon + onboarding logo). These guard
 * against the resources being dropped from `src/main/resources` or the SVGs failing to decode
 * under Skia's SVGDOM (including the romm_logo.svg embedded `<style>` class rules).
 */
class BundledImagesTest {

    @Test
    fun `window icon asset is bundled and decodes`() {
        val bitmap = loadBundledImage("/icons/rommulus_icon.svg", size = 256)
        assertNotNull(bitmap, "window icon /icons/rommulus_icon.svg must be on the classpath")
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }

    @Test
    fun `romm logo asset is bundled and decodes`() {
        val bitmap = loadBundledImage("/icons/romm_logo.svg")
        assertNotNull(bitmap, "onboarding logo /icons/romm_logo.svg must be on the classpath")
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }

    @Test
    fun `missing resource returns null`() {
        assertNull(loadBundledImage("/icons/does-not-exist.svg"))
    }
}
