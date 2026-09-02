package com.romm.androidtv.controller.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ControllerConfigMerger — merge-over-defaults semantics")
class ControllerConfigMergerTest {

    private fun primary(binding: PhysicalBinding): Map<BindingSlot, PhysicalBinding?> =
        mapOf(BindingSlot.PRIMARY to binding)

    private val snesProfile = CoreControllerProfiles.byCoreId("snes9x")
        ?: throw IllegalStateException("snes9x profile not found in catalog")

    private val n64Profile = CoreControllerProfiles.byCoreId("mupen64plus_next")
        ?: throw IllegalStateException("mupen64plus_next profile not found in catalog")

    @Test
    fun `pause chord may share stick buttons with gameplay controls`() {
        val ps2Profile = CoreControllerProfiles.byCoreId("lrps2")
            ?: throw IllegalStateException("lrps2 profile not found in catalog")
        val leftStick = PhysicalBinding.Key(NeutralKey.BUTTON_THUMBL.platformCode)
        val rightStick = PhysicalBinding.Key(NeutralKey.BUTTON_THUMBR.platformCode)

        val merged = ControllerConfigMerger.merge(
            ps2Profile,
            mapOf(
                0 to mapOf(
                    CoreControlId.L3 to mapOf(BindingSlot.PRIMARY to leftStick),
                    CoreControlId.R3 to mapOf(BindingSlot.PRIMARY to rightStick),
                ),
            ),
        ).players.getValue(0)

        assertThat(merged.get(CoreControlId.L3, BindingSlot.PRIMARY)).isEqualTo(leftStick)
        assertThat(merged.get(CoreControlId.R3, BindingSlot.PRIMARY)).isEqualTo(rightStick)
        assertThat(merged.get(CoreControlId.PAUSE_MENU, BindingSlot.PRIMARY)).isEqualTo(leftStick)
        assertThat(merged.get(CoreControlId.PAUSE_MENU, BindingSlot.SECONDARY)).isEqualTo(rightStick)
    }

    @Test
    fun `defaults preserved when no overrides`() {
        val config = ControllerConfigMerger.merge(snesProfile, emptyMap())

        assertThat(config.coreId).isEqualTo("snes9x")
        assertThat(config.players.size).isEqualTo(snesProfile.playerCount)

        for (playerIndex in 0 until snesProfile.playerCount) {
            val defaultConfig = snesProfile.defaults[playerIndex]!!
            val mergedConfig = config.players[playerIndex]!!
            assertThat(mergedConfig.bindings).isEqualTo(defaultConfig.bindings)
        }
    }

    @Test
    fun `an override replaces a default binding`() {
        val overrides = mapOf(
            0 to mapOf(
                CoreControlId.BUTTON_A to primary(PhysicalBinding.Key(14)),
            ),
        )

        val config = ControllerConfigMerger.merge(snesProfile, overrides)
        val player0 = config.players[0]!!

        // Overridden binding
        assertThat(player0[CoreControlId.BUTTON_A]).isEqualTo(PhysicalBinding.Key(14))

        // All other defaults preserved
        for (descriptor in snesProfile.controls) {
            if (descriptor.id != CoreControlId.BUTTON_A) {
                assertThat(player0[descriptor.id])
                    .isEqualTo(snesProfile.defaults[0]!![descriptor.id])
            }
        }
    }

    @Test
    fun `an unknown controlId override is ignored`() {
        // N64 has N64_C_UP which SNES does not have; injecting it as an override
        // simulates an obsolete control persisted from a different profile or app version.
        val overrides = mapOf(
            0 to mapOf(
                CoreControlId.N64_C_UP to primary(PhysicalBinding.Key(18)),
                CoreControlId.BUTTON_A to primary(PhysicalBinding.Key(23)),
            ),
        )

        val config = ControllerConfigMerger.merge(snesProfile, overrides)
        val player0 = config.players[0]!!

        // Known override applied
        assertThat(player0[CoreControlId.BUTTON_A]).isEqualTo(PhysicalBinding.Key(23))

        // Unknown control not present in merged bindings
        assertThat(player0[CoreControlId.N64_C_UP]).isNull()
        assertThat(player0.bindings.containsKey(CoreControlId.N64_C_UP)).isFalse()

        // All profile controls still covered
        for (descriptor in snesProfile.controls) {
            assertThat(player0[descriptor.id]).isNotNull()
        }
    }

    @Test
    fun `per-player isolation, player 1 override does not affect player 2`() {
        val overrides = mapOf(
            1 to mapOf(
                CoreControlId.BUTTON_B to primary(PhysicalBinding.Key(15)),
            ),
        )

        val config = ControllerConfigMerger.merge(snesProfile, overrides)

        // Player 0: no override, all defaults
        val player0 = config.players[0]!!
        assertThat(player0[CoreControlId.BUTTON_B])
            .isEqualTo(snesProfile.defaults[0]!![CoreControlId.BUTTON_B])

        // Player 1: BUTTON_B overridden
        val player1 = config.players[1]!!
        assertThat(player1[CoreControlId.BUTTON_B]).isEqualTo(PhysicalBinding.Key(15))

        // Player 1 other controls still default
        for (descriptor in snesProfile.controls) {
            if (descriptor.id != CoreControlId.BUTTON_B) {
                assertThat(player1[descriptor.id])
                    .isEqualTo(snesProfile.defaults[1]!![descriptor.id])
            }
        }
    }

    @Test
    fun `merged config covers all controls for every player`() {
        val overrides = mapOf(
            0 to mapOf(CoreControlId.START to primary(PhysicalBinding.Key(140))),
            2 to mapOf(CoreControlId.D_PAD_UP to primary(PhysicalBinding.Key(141))),
            3 to mapOf(CoreControlId.N64_C_DOWN to primary(PhysicalBinding.Key(142))),
        )

        val config = ControllerConfigMerger.merge(n64Profile, overrides)

        assertThat(config.players.size).isEqualTo(n64Profile.playerCount)

        val knownIds = n64Profile.controls.map { it.id }.toSet()
        for (playerIndex in 0 until n64Profile.playerCount) {
            val playerConfig = config.players[playerIndex]!!
            for (controlId in knownIds) {
                assertThat(playerConfig[controlId])
                    .`as`("Player $playerIndex must have binding for $controlId")
                    .isNotNull()
            }
            // No extra bindings beyond the profile's known controls
            assertThat(playerConfig.bindings.keys).containsExactlyInAnyOrderElementsOf(knownIds)
        }
    }

    @Test
    fun `playerCount players present`() {
        // SNES has 2 players
        val snesConfig = ControllerConfigMerger.merge(snesProfile, emptyMap())
        assertThat(snesConfig.players.keys).containsExactly(0, 1)

        // N64 has 4 players
        val n64Config = ControllerConfigMerger.merge(n64Profile, emptyMap())
        assertThat(n64Config.players.keys).containsExactly(0, 1, 2, 3)
    }

    @Test
    fun `multiple overrides on same player all applied`() {
        val overrides = mapOf(
            0 to mapOf(
                CoreControlId.BUTTON_A to primary(PhysicalBinding.Key(14)),
                CoreControlId.BUTTON_B to primary(PhysicalBinding.Key(23)),
                CoreControlId.START to primary(PhysicalBinding.Key(30)),
            ),
        )

        val config = ControllerConfigMerger.merge(snesProfile, overrides)
        val player0 = config.players[0]!!

        assertThat(player0[CoreControlId.BUTTON_A]).isEqualTo(PhysicalBinding.Key(14))
        assertThat(player0[CoreControlId.BUTTON_B]).isEqualTo(PhysicalBinding.Key(23))
        assertThat(player0[CoreControlId.START]).isEqualTo(PhysicalBinding.Key(30))

        // Un-overridden controls remain at defaults
        assertThat(player0[CoreControlId.BUTTON_X])
            .isEqualTo(snesProfile.defaults[0]!![CoreControlId.BUTTON_X])
    }

    @Test
    fun `override with axis binding replaces key default`() {
        val overrides = mapOf(
            0 to mapOf(
                CoreControlId.BUTTON_A to primary(PhysicalBinding.Axis(0)),
            ),
        )

        val config = ControllerConfigMerger.merge(snesProfile, overrides)
        val player0 = config.players[0]!!

        assertThat(player0[CoreControlId.BUTTON_A]).isEqualTo(PhysicalBinding.Axis(0))
    }

    @Test
    fun `override with axis direction binding replaces key default`() {
        val overrides = mapOf(
            0 to mapOf(
                CoreControlId.BUTTON_A to primary(PhysicalBinding.AxisDirection(0, 1)),
            ),
        )

        val config = ControllerConfigMerger.merge(snesProfile, overrides)
        val player0 = config.players[0]!!

        assertThat(player0[CoreControlId.BUTTON_A]).isEqualTo(PhysicalBinding.AxisDirection(0, 1))
    }

    @Test
    fun `existing override wins over a newly added secondary default`() {
        // AXIS_Y platform code is 1 (see NeutralAxis.Y).
        val stickUp = PhysicalBinding.AxisDirection(1, -1)
        val overrides = mapOf(
            0 to mapOf(
                CoreControlId.BUTTON_A to mapOf(BindingSlot.PRIMARY to stickUp),
            ),
        )

        val player = ControllerConfigMerger.merge(snesProfile, overrides).players.getValue(0)

        assertThat(player.get(CoreControlId.BUTTON_A, BindingSlot.PRIMARY)).isEqualTo(stickUp)
        assertThat(player.get(CoreControlId.D_PAD_UP, BindingSlot.SECONDARY)).isNull()
        assertThat(player.get(CoreControlId.D_PAD_UP, BindingSlot.PRIMARY))
            .isEqualTo(snesProfile.defaults.getValue(0).get(CoreControlId.D_PAD_UP))
    }

    @Test
    fun `overrides for player index beyond profile playerCount are ignored`() {
        // SNES has 2 players (0, 1); an override for player 5 should not crash.
        val overrides = mapOf(
            5 to mapOf(
                CoreControlId.BUTTON_A to primary(PhysicalBinding.Key(14)),
            ),
        )

        val config = ControllerConfigMerger.merge(snesProfile, overrides)
        assertThat(config.players.size).isEqualTo(2)
        assertThat(config.players.containsKey(5)).isFalse()

        // Player 0 defaults untouched
        assertThat(config.players[0]!![CoreControlId.BUTTON_A])
            .isEqualTo(snesProfile.defaults[0]!![CoreControlId.BUTTON_A])
    }
}
