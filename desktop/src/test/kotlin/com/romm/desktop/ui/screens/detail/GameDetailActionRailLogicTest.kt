package com.romm.desktop.ui.screens.detail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import com.romm.androidtv.library.FavoriteUiState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure-logic tests for the game-detail favorite rail's state→(icon, label) mapping — the
 * desktop mirror of Android's `GameDetailActionRail` FavoriteButtonConfig table.
 */
@DisplayName("GameDetailScreen — favorite rail icon/label mapping")
class GameDetailActionRailLogicTest {

    @Test
    fun `loading shows a border star and the pending label`() {
        val ui = favoriteRailUi(FavoriteUiState.Loading)
        assertThat(ui.icon).isSameAs(Icons.Filled.StarBorder)
        assertThat(ui.label).isEqualTo("Favorite…")
    }

    @Test
    fun `confirmed favorite shows a filled star with the Favorited label`() {
        val ui = favoriteRailUi(FavoriteUiState.Confirmed(isFavorite = true))
        assertThat(ui.icon).isSameAs(Icons.Filled.Star)
        assertThat(ui.label).isEqualTo("Favorited")
    }

    @Test
    fun `confirmed non-favorite shows a border star with the Favorite label`() {
        val ui = favoriteRailUi(FavoriteUiState.Confirmed(isFavorite = false))
        assertThat(ui.icon).isSameAs(Icons.Filled.StarBorder)
        assertThat(ui.label).isEqualTo("Favorite")
    }

    @Test
    fun `updating shows a filled star (Android parity) with the direction label`() {
        val adding = favoriteRailUi(FavoriteUiState.Updating(previous = false, target = true))
        assertThat(adding.icon).isSameAs(Icons.Filled.Star)
        assertThat(adding.label).isEqualTo("Adding…")

        val removing = favoriteRailUi(FavoriteUiState.Updating(previous = true, target = false))
        assertThat(removing.icon).isSameAs(Icons.Filled.Star)
        assertThat(removing.label).isEqualTo("Removing…")
    }
}
