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
}
