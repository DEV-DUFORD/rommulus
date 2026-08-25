package com.romm.desktop.player

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Ps2CompatibilityOverridesTest {
    @Test
    fun `Splashdown uses software hardware rendering`() {
        assertThat(Ps2CompatibilityOverrides.rendererFor("ps2", "Splashdown"))
            .isEqualTo(RendererOverride.SOFTWARE_HW)
        assertThat(
            Ps2CompatibilityOverrides.rendererFor(
                "ps2",
                "",
                "Splashdown (USA) (v1.00)",
            ),
        ).isEqualTo(RendererOverride.SOFTWARE_HW)
    }

    @Test
    fun `override does not affect sequel other games or other platforms`() {
        assertThat(Ps2CompatibilityOverrides.rendererFor("ps2", "Splashdown: Rides Gone Wild"))
            .isNull()
        assertThat(Ps2CompatibilityOverrides.rendererFor("ps2", "Kingdom Hearts II"))
            .isNull()
        assertThat(Ps2CompatibilityOverrides.rendererFor("gba", "Splashdown"))
            .isNull()
    }

    @Test
    fun `Ace Combat 04 uses its core gamefix instead of a renderer override`() {
        assertThat(
            Ps2CompatibilityOverrides.rendererFor(
                "ps2",
                "Ace Combat 04 - Shattered Skies",
            ),
        ).isNull()
    }
}
