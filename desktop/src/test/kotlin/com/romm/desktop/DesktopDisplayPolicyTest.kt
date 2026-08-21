package com.romm.desktop

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DesktopDisplayPolicyTest {

    @Test
    fun `Gamescope desktop uses borderless fullscreen`() {
        val policy = desktopDisplayPolicy(
            mapOf("XDG_CURRENT_DESKTOP" to "SteamOS:GaMeScOpE"),
        )

        assertThat(policy.fullscreen).isTrue()
        assertThat(policy.undecorated).isTrue()
    }

    @Test
    fun `ordinary and missing desktops remain decorated and floating`() {
        val gnome = desktopDisplayPolicy(mapOf("XDG_CURRENT_DESKTOP" to "GNOME"))
        val missing = desktopDisplayPolicy(emptyMap())

        assertThat(gnome).isEqualTo(DesktopDisplayPolicy())
        assertThat(missing).isEqualTo(DesktopDisplayPolicy())
    }
}
