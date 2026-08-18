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
    fun `detail screens are not top-level navigation destinations`() {
        assertThat(topLevelNavDestination(Screen.GAME_DETAIL)).isNull()
        assertThat(topLevelNavDestination(Screen.PLATFORM_DETAIL)).isNull()
        assertThat(topLevelNavDestination(Screen.COLLECTION_DETAIL)).isNull()
    }

    @Test
    fun `browse detail screens retain their section navigation destination`() {
        assertThat(libraryNavDestination(Screen.PLATFORM_DETAIL))
            .isEqualTo(DesktopNavDestination.PLATFORMS)
        assertThat(libraryNavDestination(Screen.COLLECTION_DETAIL))
            .isEqualTo(DesktopNavDestination.COLLECTIONS)
    }

    @Test
    fun `game detail retains its parent section navigation destination`() {
        assertThat(libraryNavDestination(Screen.GAME_DETAIL, Screen.COLLECTION_DETAIL))
            .isEqualTo(DesktopNavDestination.COLLECTIONS)
        assertThat(libraryNavDestination(Screen.GAME_DETAIL, Screen.PLATFORM_DETAIL))
            .isEqualTo(DesktopNavDestination.PLATFORMS)
        assertThat(libraryNavDestination(Screen.GAME_DETAIL, Screen.SEARCH))
            .isEqualTo(DesktopNavDestination.SEARCH)
    }

    @Test
    fun `settings detail screens retain the settings navigation destination`() {
        assertThat(libraryNavDestination(Screen.BIOS_CONFIGURATION))
            .isEqualTo(DesktopNavDestination.SETTINGS)
        assertThat(libraryNavDestination(Screen.LICENSE))
            .isEqualTo(DesktopNavDestination.SETTINGS)
    }
}
