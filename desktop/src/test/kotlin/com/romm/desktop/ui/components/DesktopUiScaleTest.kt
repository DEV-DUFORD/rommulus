package com.romm.desktop.ui.components

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Desktop UI scale")
class DesktopUiScaleTest {

    @Test
    fun `keeps the designed size at and below the baseline`() {
        assertThat(desktopUiScale(1280f, 800f)).isEqualTo(1f)
        assertThat(desktopUiScale(1280f, 720f)).isEqualTo(1f)
        assertThat(desktopUiScale(1024f, 600f)).isEqualTo(1f)
    }

    @Test
    fun `scales uniformly with the shorter window axis`() {
        assertThat(desktopUiScale(1920f, 1080f)).isEqualTo(1.35f)
        assertThat(desktopUiScale(3840f, 2160f)).isEqualTo(2.7f)
        assertThat(desktopUiScale(3440f, 1440f)).isEqualTo(1.8f)
    }
}
