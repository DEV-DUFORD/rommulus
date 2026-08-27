package com.romm.desktop.ui.input

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SteamVirtualKeyboardTest {

    @Test
    fun `Linux launches Steam keyboard URI`() {
        var command: List<String>? = null

        val opened = openSteamVirtualKeyboard(osName = "Linux") { command = it }

        assertThat(opened).isTrue()
        assertThat(command).containsExactly("steam", "-ifrunning", "steam://open/keyboard")
    }

    @Test
    fun `non Linux desktops do not launch Steam`() {
        var launched = false

        val opened = openSteamVirtualKeyboard(osName = "Mac OS X") { launched = true }

        assertThat(opened).isFalse()
        assertThat(launched).isFalse()
    }
}
