package com.romm.desktop.ui.input

import com.romm.desktop.platform.HostOs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SteamVirtualKeyboardTest {

    @Test
    fun `Linux launches Steam keyboard URI`() {
        var command: List<String>? = null

        val opened = SteamVirtualKeyboardLauncher { command = it }.launch()

        assertThat(opened).isTrue()
        assertThat(command).containsExactly("steam", "-ifrunning", "steam://open/keyboard")
    }

    @Test
    fun `non Linux desktops do not launch Steam`() {
        assertThat(NoopVirtualKeyboardLauncher.launch()).isFalse()
    }

    @Test
    fun `launcher is selected from the normalized host`() {
        assertThat(VirtualKeyboardLauncher.forHostOs(HostOs.LINUX))
            .isInstanceOf(SteamVirtualKeyboardLauncher::class.java)
        assertThat(VirtualKeyboardLauncher.forHostOs(HostOs.WINDOWS))
            .isInstanceOf(NoopVirtualKeyboardLauncher::class.java)
        assertThat(VirtualKeyboardLauncher.forHostOs(HostOs.MACOS))
            .isInstanceOf(NoopVirtualKeyboardLauncher::class.java)
        assertThat(VirtualKeyboardLauncher.forHostOs(HostOs.UNKNOWN))
            .isInstanceOf(NoopVirtualKeyboardLauncher::class.java)
    }
}
