package com.romm.desktop.ui.components

import org.assertj.core.api.Assertions.assertThat
import com.romm.androidtv.library.RommTheme
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class RommulusThemeTest {

    /**
     * Each RommTheme enum value must map to a non-null [RommDesktopPalette]
     * with non-null color fields. This is the only pure-logic testable
     * property of the theme system without a Compose UI harness.
     */
    @ParameterizedTest
    @EnumSource(RommTheme::class)
    fun `each theme maps to non-null palette with non-null colors`(theme: RommTheme) {
        val palette = RommDesktopPalettes.forTheme(theme)

        assertThat(palette).isNotNull()
        assertThat(palette.romm200).isNotNull()
        assertThat(palette.romm300).isNotNull()
        assertThat(palette.romm400).isNotNull()
        assertThat(palette.romm500).isNotNull()
        assertThat(palette.romm600).isNotNull()
        assertThat(palette.ink).isNotNull()
        assertThat(palette.nightHi).isNotNull()
        assertThat(palette.nightLo).isNotNull()
        assertThat(palette.stageHi).isNotNull()
        assertThat(palette.stageLo).isNotNull()
        assertThat(palette.textPrimary).isNotNull()
        assertThat(palette.textSecondary).isNotNull()
    }

    @Test
    fun `Light theme is marked as light`() {
        val palette = RommDesktopPalettes.forTheme(RommTheme.Light)
        assertThat(palette.light).isTrue()
    }

    @Test
    fun `all other themes are dark`() {
        RommTheme.entries.filterNot { it == RommTheme.Light }.forEach { theme ->
            val palette = RommDesktopPalettes.forTheme(theme)
            assertThat(palette.light).isFalse()
        }
    }

    @Test
    fun `Light theme has light text on light background`() {
        val palette = RommDesktopPalettes.forTheme(RommTheme.Light)
        // Light theme should have dark text (low hex values) on light background (high hex values)
        assertThat(palette.textPrimary.red).isLessThan(0.5f)
        assertThat(palette.nightHi.red).isGreaterThan(0.9f)
    }

    @Test
    fun `dark themes have white or near-white text primary`() {
        RommTheme.entries.filterNot { it == RommTheme.Light }.forEach { theme ->
            val palette = RommDesktopPalettes.forTheme(theme)
            assertThat(palette.textPrimary.red).isGreaterThan(0.9f)
        }
    }

    @Test
    fun `Rommulus palette has expected alpha channel`() {
        val p = RommDesktopPalettes.RomMulus
        // All colors should have full alpha (0xFF in the high byte)
        assertThat(p.romm500.alpha).isEqualTo(1.0f)
        assertThat(p.nightHi.alpha).isEqualTo(1.0f)
        assertThat(p.textPrimary.alpha).isEqualTo(1.0f)
    }
}
