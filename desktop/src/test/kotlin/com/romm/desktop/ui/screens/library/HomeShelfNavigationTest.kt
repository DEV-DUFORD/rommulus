package com.romm.desktop.ui.screens.library

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HomeShelfNavigationTest {

    private val shelves = listOf(
        HomeShelfNavigationSnapshot(HomeShelf.CONTINUE_PLAYING, listOf("continue:1", "continue:2"), 0),
        HomeShelfNavigationSnapshot(HomeShelf.RECENTLY_ADDED, listOf("recent:1", "recent:2"), 1),
        HomeShelfNavigationSnapshot(HomeShelf.FAVORITES, listOf("favorite:1"), 0),
    )

    @Test
    fun `down targets the remembered card in the next shelf`() {
        assertThat(homeShelfNavigationTarget("continue:2", shelves, moveDown = true))
            .isEqualTo(HomeShelfNavigationTarget(HomeShelf.RECENTLY_ADDED, 1, "recent:2"))
    }

    @Test
    fun `up skips omitted shelves and restores remembered position`() {
        val withoutRecent = listOf(shelves[0], shelves[2].copy(rememberedCardIndex = 9))

        assertThat(homeShelfNavigationTarget("favorite:1", withoutRecent, moveDown = false))
            .isEqualTo(HomeShelfNavigationTarget(HomeShelf.CONTINUE_PLAYING, 0, "continue:1"))
        assertThat(homeShelfNavigationTarget("continue:1", withoutRecent, moveDown = true))
            .isEqualTo(HomeShelfNavigationTarget(HomeShelf.FAVORITES, 0, "favorite:1"))
    }

    @Test
    fun `navigation declines at shelf edges or for unrelated focus`() {
        assertThat(homeShelfNavigationTarget("continue:1", shelves, moveDown = false)).isNull()
        assertThat(homeShelfNavigationTarget("favorite:1", shelves, moveDown = true)).isNull()
        assertThat(homeShelfNavigationTarget("nav:HOME", shelves, moveDown = true)).isNull()
    }
}
