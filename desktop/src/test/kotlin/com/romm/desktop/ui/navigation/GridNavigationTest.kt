package com.romm.desktop.ui.navigation

import androidx.compose.ui.focus.FocusDirection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for positional grid navigation: the pure [positionalGridNeighbor] row-stride math (desktop
 * mirror of Android's `RomGridFocusNavigationTest`) and [FocusNavigator]'s Up/Down routing to an
 * installed grid handler (the controller D-pad path).
 */
@DisplayName("Positional grid navigation")
class GridNavigationTest {

    // ── positionalGridNeighbor (Android RomGridFocusNavigationTest parity) ──────────────

    @Test
    fun `moving down retains the card column`() {
        assertThat(positionalGridNeighbor(6, 4, 20, moveDown = true)).isEqualTo(10)
    }

    @Test
    fun `moving up retains the card column`() {
        assertThat(positionalGridNeighbor(14, 4, 20, moveDown = false)).isEqualTo(10)
    }

    @Test
    fun `moving beyond a grid edge leaves focus navigation to Compose`() {
        assertThat(positionalGridNeighbor(2, 4, 20, moveDown = false)).isNull()
        assertThat(positionalGridNeighbor(18, 4, 20, moveDown = true)).isNull()
    }

    @Test
    fun `invalid input declines the move`() {
        assertThat(positionalGridNeighbor(-1, 4, 20, moveDown = true)).isNull()
        assertThat(positionalGridNeighbor(20, 4, 20, moveDown = false)).isNull()
        assertThat(positionalGridNeighbor(3, 0, 20, moveDown = true)).isNull()
    }

    // ── FocusNavigator grid routing (controller D-pad path) ─────────────────────────────

    @Test
    fun `up and down route to the installed grid handler`() {
        val navigator = FocusNavigator()
        var handledDirections = mutableListOf<FocusDirection>()
        navigator.installGridNavigation(Any()) { direction ->
            handledDirections.add(direction)
            direction == FocusDirection.Down // handle Down, decline Up
        }

        // Down: the handler accepts → consumed, the fallback never runs.
        assertThat(navigator.moveSpatialFocus(FocusDirection.Down) { error("fallback must not run") })
            .isTrue()

        // Up: the handler declines (returns false) → the fallback runs.
        var fellBack = false
        assertThat(navigator.moveSpatialFocus(FocusDirection.Up) { fellBack = true; true }).isTrue()
        assertThat(fellBack).isTrue()

        assertThat(handledDirections).containsExactly(FocusDirection.Down, FocusDirection.Up)
    }

    @Test
    fun `left and right bypass the grid handler`() {
        val navigator = FocusNavigator()
        var handled = false
        navigator.installGridNavigation(Any()) { handled = true; true }

        var fellBack = false
        assertThat(navigator.moveSpatialFocus(FocusDirection.Left) { fellBack = true; true }).isTrue()
        assertThat(handled).isFalse()
        assertThat(fellBack).isTrue()

        fellBack = false
        assertThat(navigator.moveSpatialFocus(FocusDirection.Right) { fellBack = true; true }).isTrue()
        assertThat(handled).isFalse()
        assertThat(fellBack).isTrue()
    }

    @Test
    fun `removing the grid handler restores default routing`() {
        val navigator = FocusNavigator()
        val owner = Any()
        var handled = false
        navigator.installGridNavigation(owner) { handled = true; true }

        navigator.removeGridNavigation(owner)

        var fellBack = false
        assertThat(navigator.moveSpatialFocus(FocusDirection.Down) { fellBack = true; true }).isTrue()
        assertThat(handled).isFalse()
        assertThat(fellBack).isTrue()
    }

    @Test
    fun `removing a different owner keeps the active grid handler`() {
        val navigator = FocusNavigator()
        val active = Any()
        var handled = false
        navigator.installGridNavigation(active) { handled = true; true }

        navigator.removeGridNavigation(Any()) // not the active owner — no-op

        assertThat(navigator.moveSpatialFocus(FocusDirection.Down) { error("fallback must not run") })
            .isTrue()
        assertThat(handled).isTrue()
    }

    @Test
    fun `a modal spatial override still wins over the grid handler`() {
        val navigator = FocusNavigator()
        var gridHandled = false
        navigator.installGridNavigation(Any()) { gridHandled = true; true }
        var modalHandled = false
        navigator.installSpatialFocusOverride(Any(), { modalHandled = true; true }, onBack = {})

        assertThat(navigator.moveSpatialFocus(FocusDirection.Down) { error("fallback must not run") })
            .isTrue()
        assertThat(modalHandled).isTrue()
        assertThat(gridHandled).isFalse()
    }

    @Test
    fun `without a grid handler, up and down fall back to default traversal`() {
        val navigator = FocusNavigator()
        var fellBack = false
        assertThat(navigator.moveSpatialFocus(FocusDirection.Up) { fellBack = true; true }).isTrue()
        assertThat(fellBack).isTrue()
    }
}
