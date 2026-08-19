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
    fun `secondary override preserves primary binding`() = runTest {
        val secondary = PhysicalBinding.AxisDirection(android.view.MotionEvent.AXIS_X, 1)

        repository.setBinding(
            "snes9x",
            player0,
            CoreControlId.BUTTON_A,
            secondary,
            BindingSlot.SECONDARY,
        )
        val player = repository.loadCore("snes9x").players.getValue(player0)

        assertThat(player.get(CoreControlId.BUTTON_A, BindingSlot.PRIMARY))
            .isEqualTo(snesProfile.defaults.getValue(player0).get(CoreControlId.BUTTON_A))
        assertThat(player.get(CoreControlId.BUTTON_A, BindingSlot.SECONDARY)).isEqualTo(secondary)
    }

    @Test
    fun `clearBinding explicitly unmaps only the selected control slot`() = runTest {
        repository.clearBinding("snes9x", player0, CoreControlId.BUTTON_A, BindingSlot.PRIMARY)
        val player = repository.loadCore("snes9x").players.getValue(player0)

        assertThat(player.get(CoreControlId.BUTTON_A, BindingSlot.PRIMARY)).isNull()
        assertThat(player.get(CoreControlId.BUTTON_A, BindingSlot.SECONDARY))
            .isEqualTo(snesProfile.defaults.getValue(player0).get(CoreControlId.BUTTON_A, BindingSlot.SECONDARY))
        assertThat(player.get(CoreControlId.BUTTON_B, BindingSlot.PRIMARY))
            .isEqualTo(snesProfile.defaults.getValue(player0).get(CoreControlId.BUTTON_B, BindingSlot.PRIMARY))
    }

    @Test
    fun `pause menu combination persists independently of core controls`() = runTest {
        repository.setBinding(
            "snes9x",
            player0,
            CoreControlId.PAUSE_MENU,
            PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_L2),
            BindingSlot.PRIMARY,
        )
        repository.setBinding(
            "snes9x",
            player0,
            CoreControlId.PAUSE_MENU,
            PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_R2),
            BindingSlot.SECONDARY,
        )

        val config = repository.loadCore("snes9x")
        val pauseBindings = config.players.getValue(player0).bindings.getValue(CoreControlId.PAUSE_MENU)

        assertThat(pauseBindings.primary)
            .isEqualTo(PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_L2))
        assertThat(pauseBindings.secondary)
            .isEqualTo(PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_R2))
        assertThat(config.toRouterMappings(snesProfile).getValue(player0).pauseMenuCombination)
            .isNotNull()
    }

    @Test
    fun `swapBindings exchanges individual slots`() = runTest {
        val secondary = PhysicalBinding.Key(14)
        repository.setBinding(
            "snes9x",
            player0,
            CoreControlId.BUTTON_A,
            secondary,
            BindingSlot.SECONDARY,
        )
        val aSecondary = BindingAddress(CoreControlId.BUTTON_A, BindingSlot.SECONDARY)
        val bPrimary = BindingAddress(CoreControlId.BUTTON_B, BindingSlot.PRIMARY)
        val oldBPrimary = snesProfile.defaults.getValue(player0).get(CoreControlId.BUTTON_B)

        repository.swapBindings("snes9x", player0, aSecondary, bPrimary)
        val player = repository.loadCore("snes9x").players.getValue(player0)

        assertThat(player.get(CoreControlId.BUTTON_A, BindingSlot.SECONDARY)).isEqualTo(oldBPrimary)
        assertThat(player.get(CoreControlId.BUTTON_B, BindingSlot.PRIMARY)).isEqualTo(secondary)
    }

    @Test
    fun `swapBindings swaps two controls bindings`() = runTest {
        val a = PhysicalBinding.Key(14)
        val b = PhysicalBinding.Key(23)
        repository.setBinding("snes9x", player0, CoreControlId.BUTTON_A, a)
        repository.setBinding("snes9x", player0, CoreControlId.BUTTON_B, b)

        repository.swapBindings(
            "snes9x",
            player0,
            BindingAddress(CoreControlId.BUTTON_A, BindingSlot.PRIMARY),
            BindingAddress(CoreControlId.BUTTON_B, BindingSlot.PRIMARY),
        )
        val config = repository.loadCore("snes9x")

        assertThat(config.players[player0]!![CoreControlId.BUTTON_A]).isEqualTo(b)
        assertThat(config.players[player0]!![CoreControlId.BUTTON_B]).isEqualTo(a)
    }

    @Test
    fun `swapBindings moves a binding when only one control exists`() = runTest {
        val a = PhysicalBinding.Key(14)
        repository.setBinding("snes9x", player0, CoreControlId.BUTTON_A, a)

        repository.swapBindings(
            "snes9x",
            player0,
            BindingAddress(CoreControlId.BUTTON_A, BindingSlot.PRIMARY),
            BindingAddress(CoreControlId.BUTTON_B, BindingSlot.PRIMARY),
        )
        val config = repository.loadCore("snes9x")

        // The effective values are exchanged, including catalog defaults.
        assertThat(config.players[player0]!![CoreControlId.BUTTON_A])
            .isEqualTo(snesProfile.defaults[player0]!![CoreControlId.BUTTON_B])
        assertThat(config.players[player0]!![CoreControlId.BUTTON_B]).isEqualTo(a)
    }

    @Test
    fun `replaceBinding replaces a binding and removes a conflicting duplicate`() = runTest {
        val conflicting = PhysicalBinding.Key(14)
        // Both controls currently map to the same physical input.
        repository.setBinding("snes9x", player0, CoreControlId.BUTTON_A, conflicting)
        repository.setBinding("snes9x", player0, CoreControlId.BUTTON_B, conflicting)

        repository.replaceBinding(
            "snes9x",
            player0,
            BindingAddress(CoreControlId.BUTTON_A, BindingSlot.PRIMARY),
            conflicting,
        )
        val config = repository.loadCore("snes9x")

        // A keeps the binding; B is explicitly unmapped so its conflicting default cannot return.
        assertThat(config.players[player0]!![CoreControlId.BUTTON_A]).isEqualTo(conflicting)
        assertThat(config.players[player0]!![CoreControlId.BUTTON_B]).isNull()
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
    fun `clearPlayerMappings unmaps every control only for that player`() = runTest {
        repository.clearPlayerMappings("snes9x", player0)
        val config = repository.loadCore("snes9x")

        assertThat(config.players.getValue(player0).bindings.values)
            .allSatisfy { bindings ->
                assertThat(bindings.primary).isNull()
                assertThat(bindings.secondary).isNull()
            }
        assertThat(config.players.getValue(player1)[CoreControlId.BUTTON_A])
            .isEqualTo(snesProfile.defaults.getValue(player1)[CoreControlId.BUTTON_A])
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
