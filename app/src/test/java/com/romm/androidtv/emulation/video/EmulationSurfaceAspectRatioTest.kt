package com.romm.androidtv.emulation.video

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmulationSurfaceAspectRatioTest {

    @Test
    fun `reported aspect ratio overrides interlaced framebuffer dimensions`() {
        assertThat(resolveDisplayAspectRatio(320, 480, 4f / 3f)).isEqualTo(4f / 3f)
    }

    @Test
    fun `frame dimensions remain the fallback when the core reports no aspect`() {
        assertThat(resolveDisplayAspectRatio(320, 240, 0f)).isEqualTo(4f / 3f)
    }

    @Test
    fun `fitted size is height-constrained for a GBA aspect ratio`() {
        assertThat(computeAspectFittedSize(3f / 2f, maxWidthPx = 1920, maxHeightPx = 1080))
            .isEqualTo(androidx.compose.ui.unit.IntSize(1620, 1080))
    }

    @Test
    fun `fitted size is width-constrained when height has room`() {
        assertThat(computeAspectFittedSize(4f / 3f, maxWidthPx = 1440, maxHeightPx = 1200))
            .isEqualTo(androidx.compose.ui.unit.IntSize(1440, 1080))
    }

    @Test
    fun `scale is width-constrained when available width is the binding constraint`() {
        assertThat(computeIntegerScale(320, 240, 4f / 3f, maxWidthPx = 1920f, maxHeightPx = 100_000f))
            .isEqualTo(6)
    }

    @Test
    fun `scale is height-constrained when available height is the binding constraint`() {
        assertThat(computeIntegerScale(320, 240, 4f / 3f, maxWidthPx = 100_000f, maxHeightPx = 960f))
            .isEqualTo(4)
    }

    @Test
    fun `scale respects an aspect wider than the native pixel aspect`() {
        // 16:9 (1.777) > pixel aspect 4:3 (1.333) narrows the available width.
        assertThat(computeIntegerScale(320, 240, 16f / 9f, maxWidthPx = 1600f, maxHeightPx = 1080f))
            .isEqualTo(3)
    }

    @Test
    fun `scale respects an aspect narrower than the native pixel aspect`() {
        // 1:1 (1.0) < pixel aspect 4:3: the aspect-corrected display width (240) is
        // narrower than the native core width (320), so the buffer must not exceed the box.
        assertThat(computeIntegerScale(320, 240, 1f, maxWidthPx = 1920f, maxHeightPx = 1080f))
            .isEqualTo(4)
    }

    @Test
    fun `scale falls back to zero when no integer multiple fits`() {
        assertThat(computeIntegerScale(320, 240, 4f / 3f, maxWidthPx = 320f, maxHeightPx = 200f))
            .isEqualTo(0)
    }

    @Test
    fun `scale is capped so the buffer dimensions cannot overflow int`() {
        assertThat(computeIntegerScale(2_000_000_000, 1000, 2f, maxWidthPx = 1e12f, maxHeightPx = 1e12f))
            .isEqualTo(1)
    }
}
