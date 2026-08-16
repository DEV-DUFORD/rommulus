package com.romm.desktop.ui.navigation

import com.romm.desktop.Screen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DesktopNavRailTest {

    @Test
    fun `top-level screens map to navigation destinations`() {
        assertThat(topLevelNavDestination(Screen.HOME)).isEqualTo(DesktopNavDestination.HOME)
        assertThat(topLevelNavDestination(Screen.PLATFORMS)).isEqualTo(DesktopNavDestination.PLATFORMS)
        assertThat(topLevelNavDestination(Screen.COLLECTIONS)).isEqualTo(DesktopNavDestination.COLLECTIONS)
        assertThat(topLevelNavDestination(Screen.SEARCH)).isEqualTo(DesktopNavDestination.SEARCH)
        assertThat(topLevelNavDestination(Screen.SETTINGS)).isEqualTo(DesktopNavDestination.SETTINGS)
    }

    @Test
    fun `detail screens do not render the navigation rail`() {
        assertThat(topLevelNavDestination(Screen.GAME_DETAIL)).isNull()
        assertThat(topLevelNavDestination(Screen.PLATFORM_DETAIL)).isNull()
        assertThat(topLevelNavDestination(Screen.COLLECTION_DETAIL)).isNull()
    }
}
