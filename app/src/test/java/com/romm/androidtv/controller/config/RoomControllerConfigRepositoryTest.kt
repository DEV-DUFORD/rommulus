package com.romm.androidtv.controller.config

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RoomControllerConfigRepository — persistence, merge, conflict, reset")
class RoomControllerConfigRepositoryTest {

    private val snesProfile = CoreControllerProfiles.byCoreId("snes9x")
        ?: throw IllegalStateException("snes9x profile not found in catalog")

    private val dao = FakeControllerBindingDao()
    private val repository = RoomControllerConfigRepository(dao = dao)

    private val player0 = 0
    private val player1 = 1

    @BeforeEach
    fun setUp() {
        dao.clear()
    }

    @Test
    fun `setBinding then loadCore reflects the override merged over defaults`() = runTest {
        val newBinding = PhysicalBinding.Key(14)

        repository.setBinding("snes9x", player0, CoreControlId.BUTTON_A, newBinding)
        val config = repository.loadCore("snes9x")

        assertThat(config.players[player0]!![CoreControlId.BUTTON_A]).isEqualTo(newBinding)
        // Un-overridden controls keep catalog defaults.
        assertThat(config.players[player0]!![CoreControlId.BUTTON_B])
            .isEqualTo(snesProfile.defaults[player0]!![CoreControlId.BUTTON_B])
    }

    @Test
    fun `observeCore emits the merged config after a setBinding`() = runTest {
        val newBinding = PhysicalBinding.Key(23)
        val initial = repository.observeCore("snes9x").first()
        assertThat(initial.players[player0]!![CoreControlId.BUTTON_A])
            .isEqualTo(snesProfile.defaults[player0]!![CoreControlId.BUTTON_A])

        repository.setBinding("snes9x", player0, CoreControlId.BUTTON_A, newBinding)
        val updated = repository.observeCore("snes9x").first()

        assertThat(updated.players[player0]!![CoreControlId.BUTTON_A]).isEqualTo(newBinding)
        assertThat(updated.players[player0]!![CoreControlId.BUTTON_B])
            .isEqualTo(snesProfile.defaults[player0]!![CoreControlId.BUTTON_B])
    }

    @Test
    fun `swapBindings swaps two controls bindings`() = runTest {
        val a = PhysicalBinding.Key(14)
        val b = PhysicalBinding.Key(23)
        repository.setBinding("snes9x", player0, CoreControlId.BUTTON_A, a)
        repository.setBinding("snes9x", player0, CoreControlId.BUTTON_B, b)

        repository.swapBindings("snes9x", player0, CoreControlId.BUTTON_A, CoreControlId.BUTTON_B)
        val config = repository.loadCore("snes9x")

        assertThat(config.players[player0]!![CoreControlId.BUTTON_A]).isEqualTo(b)
        assertThat(config.players[player0]!![CoreControlId.BUTTON_B]).isEqualTo(a)
    }

    @Test
    fun `swapBindings moves a binding when only one control exists`() = runTest {
        val a = PhysicalBinding.Key(14)
        repository.setBinding("snes9x", player0, CoreControlId.BUTTON_A, a)

        repository.swapBindings("snes9x", player0, CoreControlId.BUTTON_A, CoreControlId.BUTTON_B)
        val config = repository.loadCore("snes9x")

        // Binding moved from A to B; A is back to default.
        assertThat(config.players[player0]!![CoreControlId.BUTTON_A])
            .isEqualTo(snesProfile.defaults[player0]!![CoreControlId.BUTTON_A])
        assertThat(config.players[player0]!![CoreControlId.BUTTON_B]).isEqualTo(a)
    }

    @Test
    fun `replaceBinding replaces a binding and removes a conflicting duplicate`() = runTest {
        val conflicting = PhysicalBinding.Key(14)
        // Both controls currently map to the same physical input.
        repository.setBinding("snes9x", player0, CoreControlId.BUTTON_A, conflicting)
        repository.setBinding("snes9x", player0, CoreControlId.BUTTON_B, conflicting)

        repository.replaceBinding("snes9x", player0, CoreControlId.BUTTON_A, conflicting)
        val config = repository.loadCore("snes9x")

        // A keeps the binding; B is cleared back to default (no duplicate physical input).
        assertThat(config.players[player0]!![CoreControlId.BUTTON_A]).isEqualTo(conflicting)
        assertThat(config.players[player0]!![CoreControlId.BUTTON_B])
            .isEqualTo(snesProfile.defaults[player0]!![CoreControlId.BUTTON_B])
    }

    @Test
    fun `resetPlayer clears only that player`() = runTest {
        val binding = PhysicalBinding.Key(14)
        repository.setBinding("snes9x", player0, CoreControlId.BUTTON_A, binding)
        repository.setBinding("snes9x", player1, CoreControlId.BUTTON_A, binding)

        repository.resetPlayer("snes9x", player0)
        val config = repository.loadCore("snes9x")

        // Player 0 reset to defaults; player 1 override survives.
        assertThat(config.players[player0]!![CoreControlId.BUTTON_A])
            .isEqualTo(snesProfile.defaults[player0]!![CoreControlId.BUTTON_A])
        assertThat(config.players[player1]!![CoreControlId.BUTTON_A]).isEqualTo(binding)
    }

    @Test
    fun `resetCore clears the whole core`() = runTest {
        val binding = PhysicalBinding.Key(14)
        repository.setBinding("snes9x", player0, CoreControlId.BUTTON_A, binding)
        repository.setBinding("snes9x", player1, CoreControlId.BUTTON_B, PhysicalBinding.Key(23))

        repository.resetCore("snes9x")
        val config = repository.loadCore("snes9x")

        assertThat(config.players[player0]!!.bindings)
            .isEqualTo(snesProfile.defaults[player0]!!.bindings)
        assertThat(config.players[player1]!!.bindings)
            .isEqualTo(snesProfile.defaults[player1]!!.bindings)
        assertThat(dao.rows.filter { it.coreId == "snes9x" }).isEmpty()
    }

    @Test
    fun `unknown coreId returns an empty config without crashing`() = runTest {
        val config = repository.loadCore("no_such_core")

        assertThat(config.coreId).isEqualTo("no_such_core")
        assertThat(config.players).isEmpty()
    }

    @Test
    fun `per-player isolation`() = runTest {
        val binding = PhysicalBinding.Key(14)
        repository.setBinding("snes9x", player0, CoreControlId.BUTTON_A, binding)

        val config = repository.loadCore("snes9x")

        // Player 0 overridden; player 1 untouched defaults.
        assertThat(config.players[player0]!![CoreControlId.BUTTON_A]).isEqualTo(binding)
        assertThat(config.players[player1]!![CoreControlId.BUTTON_A])
            .isEqualTo(snesProfile.defaults[player1]!![CoreControlId.BUTTON_A])
    }
}
