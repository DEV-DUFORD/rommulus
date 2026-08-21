package com.romm.desktop

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DesktopDisplayPolicyTest {

    @Test
    fun `Gamescope desktop uses fullscreen`() {
        val policy = desktopDisplayPolicy(
            mapOf("XDG_CURRENT_DESKTOP" to "SteamOS:GaMeScOpE"),
        )

        assertThat(policy.fullscreen).isTrue()
    }

    @Test
    fun `ordinary and missing desktops remain floating`() {
        assertThat(
            desktopDisplayPolicy(mapOf("XDG_CURRENT_DESKTOP" to "GNOME")).fullscreen,
        ).isFalse()
        assertThat(desktopDisplayPolicy(emptyMap()).fullscreen).isFalse()
    }
}
