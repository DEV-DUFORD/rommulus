package com.romm.androidtv.controller.config

import com.romm.androidtv.controller.model.ControllerSlot
import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.model.NeutralKey
import com.romm.androidtv.emulation.model.CoreManifest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CoreControllerProfiles — catalog completeness and mapping")
class CoreControllerProfilesTest {

    private val profiles = CoreControllerProfiles.all

    @Test
    fun `every approved core has exactly one profile and no extra profiles exist`() {
        // The project-owned synthetic `test_core` harness entry (Phase 8) is not a console —
        // it intentionally has no controller profile — so scope the invariant to real cores.
        val approvedIds = CoreManifest.approvedEntries().map { it.coreId } - setOf("test_core")
        val profileIds = profiles.map { it.coreId }

        assertThat(profileIds).containsExactlyInAnyOrderElementsOf(approvedIds)
        // No duplicates and a one-to-one correspondence in both directions.
        assertThat(profileIds).hasSize(approvedIds.size)
        assertThat(profileIds.distinct()).hasSameSizeAs(profileIds)
    }

    @Test
    fun `forApprovedCores returns all profiles and byCoreId finds each one`() {
        assertThat(CoreControllerProfiles.forApprovedCores()).hasSameSizeAs(profiles)
        for (profile in profiles) {
            assertThat(CoreControllerProfiles.byCoreId(profile.coreId)).isSameAs(profile)
        }
    }

    @Test
    fun `every profile coreId is an approved CoreManifest entry`() {
        val approvedIds = CoreManifest.approvedEntries().map { it.coreId }.toSet()
        for (profile in profiles) {
            assertThat(profile.coreId).`as`("coreId for %s", profile.consoleName).isIn(approvedIds)
        }
    }

    @Test
    fun `playerCount is in 1 until ControllerSlot SLOT_COUNT`() {
        for (profile in profiles) {
            assertThat(profile.playerCount)
                .`as`("playerCount for %s", profile.coreId)
                .isBetween(1, ControllerSlot.SLOT_COUNT)
        }
    }

    @Test
    fun `every profile has a defaults entry for each player index 0 until playerCount`() {
        for (profile in profiles) {
            val expectedKeys = (0 until profile.playerCount).toSet()
            assertThat(profile.defaults.keys)
                .`as`("defaults keys for %s", profile.coreId)
                .isEqualTo(expectedKeys)
        }

    }

    @Test
    fun `every core defaults pause menu to holding L3 and R3`() {
        for (profile in profiles) {
            for (player in profile.defaults.values) {
                assertThat(player.get(CoreControlId.PAUSE_MENU, BindingSlot.PRIMARY))
                    .isEqualTo(PhysicalBinding.Key(NeutralKey.BUTTON_THUMBL.platformCode))
                assertThat(player.get(CoreControlId.PAUSE_MENU, BindingSlot.SECONDARY))
                    .isEqualTo(PhysicalBinding.Key(NeutralKey.BUTTON_THUMBR.platformCode))
            }
        }
    }

    @Test
    fun `every default binding references a control declared in the profile`() {
        for (profile in profiles) {
            val controlIds = profile.controls.map { it.id }.toSet()
            for (config in profile.defaults.values) {
                for (controlId in config.bindings.keys) {
                    assertThat(controlId)
                        .`as`("default binding control %s for %s", controlId, profile.coreId)
                        .isIn(controlIds)
                }

                @Test
                fun `digital-only profiles default left stick directions as secondary D-pad bindings`() {
                    val digitalProfiles = profiles.filter { profile ->
                        profile.controls.none { it.inputKind == InputKind.ANALOG_STICK }
                    }

                    for (profile in digitalProfiles) {
                        for (player in profile.defaults.values) {
                            assertThat(player.get(CoreControlId.D_PAD_UP, BindingSlot.SECONDARY))
                                .isEqualTo(
                                    PhysicalBinding.AxisDirection(NeutralAxis.Y.platformCode, -1),
                                )
                            assertThat(player.get(CoreControlId.D_PAD_DOWN, BindingSlot.SECONDARY))
                                .isEqualTo(
                                    PhysicalBinding.AxisDirection(NeutralAxis.Y.platformCode, 1),
                                )
                            assertThat(player.get(CoreControlId.D_PAD_LEFT, BindingSlot.SECONDARY))
                                .isEqualTo(
                                    PhysicalBinding.AxisDirection(NeutralAxis.X.platformCode, -1),
                                )
                            assertThat(player.get(CoreControlId.D_PAD_RIGHT, BindingSlot.SECONDARY))
                                .isEqualTo(
                                    PhysicalBinding.AxisDirection(NeutralAxis.X.platformCode, 1),
                                )
                        }
                    }
                }

                @Test
                fun `analog profiles do not alias left stick to D-pad`() {
                    val analogProfiles = profiles.filter { profile ->
                        profile.controls.any { it.inputKind == InputKind.ANALOG_STICK }
                    }

                    @Test
                    fun `trigger controls default digital buttons and analog trigger axes`() {
                        val playStation = CoreControllerProfiles.byCoreId("pcsx_rearmed")!!
                        val player = playStation.defaults.getValue(0)

                        assertThat(player.get(CoreControlId.L2, BindingSlot.PRIMARY))
                            .isEqualTo(PhysicalBinding.Key(NeutralKey.BUTTON_L2.platformCode))
                        assertThat(player.get(CoreControlId.L2, BindingSlot.SECONDARY))
                            .isEqualTo(PhysicalBinding.Axis(NeutralAxis.LTRIGGER.platformCode))
                        assertThat(player.get(CoreControlId.R2, BindingSlot.PRIMARY))
                            .isEqualTo(PhysicalBinding.Key(NeutralKey.BUTTON_R2.platformCode))
                        assertThat(player.get(CoreControlId.R2, BindingSlot.SECONDARY))
                            .isEqualTo(PhysicalBinding.Axis(NeutralAxis.RTRIGGER.platformCode))
                    }

                    @Test
                    fun `PlayStation right stick defaults support RX-RY and Xbox Z-RZ layouts`() {
                        val player = CoreControllerProfiles.byCoreId("pcsx_rearmed")!!.defaults.getValue(0)

                        assertThat(player.get(CoreControlId.RIGHT_STICK_X, BindingSlot.PRIMARY))
                            .isEqualTo(PhysicalBinding.Axis(NeutralAxis.RX.platformCode))
                        assertThat(player.get(CoreControlId.RIGHT_STICK_X, BindingSlot.SECONDARY))
                            .isEqualTo(PhysicalBinding.Axis(NeutralAxis.Z.platformCode))
                        assertThat(player.get(CoreControlId.RIGHT_STICK_Y, BindingSlot.PRIMARY))
                            .isEqualTo(PhysicalBinding.Axis(NeutralAxis.RY.platformCode))
                        assertThat(player.get(CoreControlId.RIGHT_STICK_Y, BindingSlot.SECONDARY))
                            .isEqualTo(PhysicalBinding.Axis(NeutralAxis.RZ.platformCode))
                    }

                    assertThat(analogProfiles.map { it.coreId })
                        .containsExactlyInAnyOrder("pcsx_rearmed", "mupen64plus_next")
                    for (profile in analogProfiles) {
                        for (player in profile.defaults.values) {
                            assertThat(player.get(CoreControlId.D_PAD_UP, BindingSlot.SECONDARY)).isNull()
                            assertThat(player.get(CoreControlId.D_PAD_DOWN, BindingSlot.SECONDARY)).isNull()
                            assertThat(player.get(CoreControlId.D_PAD_LEFT, BindingSlot.SECONDARY)).isNull()
                            assertThat(player.get(CoreControlId.D_PAD_RIGHT, BindingSlot.SECONDARY)).isNull()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `control ids are unique within each profile`() {
        for (profile in profiles) {
            val ids = profile.controls.map { it.id }
            assertThat(ids.distinct())
                .`as`("control ids for %s", profile.coreId)
                .hasSameSizeAs(ids)
        }
    }

    @Test
    fun `highlight region ids are unique within each profile`() {
        for (profile in profiles) {
            val ids = profile.controls.map { it.highlightRegion.id }
            assertThat(ids.distinct())
                .`as`("region ids for %s", profile.coreId)
                .hasSameSizeAs(ids)
        }
    }

    @Test
    fun `every control has a valid highlight region passing bounds validation`() {
        for (profile in profiles) {
            for (control in profile.controls) {
                val region = control.highlightRegion
                // No NaN/Infinite coordinates anywhere.
                assertThat(region.x).`as`("region x for %s/%s", profile.coreId, control.id).isFinite()
                assertThat(region.y).`as`("region y for %s/%s", profile.coreId, control.id).isFinite()
                assertThat(region.width).`as`("region width for %s/%s", profile.coreId, control.id).isFinite()
                assertThat(region.height).`as`("region height for %s/%s", profile.coreId, control.id).isFinite()
                // All coordinates within normalized 0..1 bounds.
                assertThat(region.x).`as`("region x for %s/%s", profile.coreId, control.id)
                    .isBetween(0f, 1f)
                assertThat(region.y).`as`("region y for %s/%s", profile.coreId, control.id)
                    .isBetween(0f, 1f)
                assertThat(region.width).`as`("region width for %s/%s", profile.coreId, control.id)
                    .isGreaterThan(0f)
                assertThat(region.height).`as`("region height for %s/%s", profile.coreId, control.id)
                    .isGreaterThan(0f)
                // Region stays within the artwork viewBox (no off-canvas hotspot).
                assertThat(region.x + region.width)
                    .`as`("region x+width for %s/%s", profile.coreId, control.id)
                    .isLessThanOrEqualTo(1.0001f)
                assertThat(region.y + region.height)
                    .`as`("region y+height for %s/%s", profile.coreId, control.id)
                    .isLessThanOrEqualTo(1.0001f)
            }
        }
    }

    // NOTE: the artwork-resourceName -> drawable resolution test lives in :app
    // (CoreControllerProfilesArtworkTest) because ControllerArtworkResolver is an
    // Android resource-lookup adapter; the shared catalog only carries the metadata.

    // --- Documentation-as-code: default console-control -> LogicalControl mapping ---

    private fun targetFor(profile: CoreControllerProfile, id: CoreControlId): LogicalControl {
        val control = profile.controls.first { it.id == id }
        return control.target
    }

    @Test
    fun `genesis_plus_gx maps Genesis controls to their RetroPad targets`() {
        val p = CoreControllerProfiles.byCoreId("genesis_plus_gx")!!
        assertThat(targetFor(p, CoreControlId.BUTTON_C)).isEqualTo(LogicalControl.BUTTON_A)
        assertThat(targetFor(p, CoreControlId.BUTTON_A)).isEqualTo(LogicalControl.BUTTON_Y)
        assertThat(targetFor(p, CoreControlId.BUTTON_B)).isEqualTo(LogicalControl.BUTTON_B)
        assertThat(targetFor(p, CoreControlId.BUTTON_Y)).isEqualTo(LogicalControl.BUTTON_X)
        assertThat(targetFor(p, CoreControlId.MODE)).isEqualTo(LogicalControl.BUTTON_SELECT)
        assertThat(targetFor(p, CoreControlId.START)).isEqualTo(LogicalControl.BUTTON_START)
    }

    @Test
    fun `snes9x maps SNES controls to their RetroPad targets`() {
        val p = CoreControllerProfiles.byCoreId("snes9x")!!
        assertThat(targetFor(p, CoreControlId.BUTTON_A)).isEqualTo(LogicalControl.BUTTON_A)
        assertThat(targetFor(p, CoreControlId.BUTTON_B)).isEqualTo(LogicalControl.BUTTON_B)
        assertThat(targetFor(p, CoreControlId.BUTTON_X)).isEqualTo(LogicalControl.BUTTON_X)
        assertThat(targetFor(p, CoreControlId.L1)).isEqualTo(LogicalControl.BUTTON_LB)
        assertThat(targetFor(p, CoreControlId.R1)).isEqualTo(LogicalControl.BUTTON_RB)
        assertThat(targetFor(p, CoreControlId.SELECT)).isEqualTo(LogicalControl.BUTTON_SELECT)
    }

    @Test
    fun `fceumm maps NES controls to their RetroPad targets`() {
        val p = CoreControllerProfiles.byCoreId("fceumm")!!
        assertThat(targetFor(p, CoreControlId.BUTTON_A)).isEqualTo(LogicalControl.BUTTON_A)
        assertThat(targetFor(p, CoreControlId.BUTTON_B)).isEqualTo(LogicalControl.BUTTON_B)
        assertThat(targetFor(p, CoreControlId.SELECT)).isEqualTo(LogicalControl.BUTTON_SELECT)
        assertThat(targetFor(p, CoreControlId.START)).isEqualTo(LogicalControl.BUTTON_START)
    }

    @Test
    fun `mupen64plus_next maps N64 controls to their RetroPad targets`() {
        val p = CoreControllerProfiles.byCoreId("mupen64plus_next")!!
        assertThat(targetFor(p, CoreControlId.BUTTON_A)).isEqualTo(LogicalControl.BUTTON_B)
        assertThat(targetFor(p, CoreControlId.BUTTON_B)).isEqualTo(LogicalControl.BUTTON_Y)
        assertThat(targetFor(p, CoreControlId.N64_C_DOWN)).isEqualTo(LogicalControl.BUTTON_A)
        assertThat(targetFor(p, CoreControlId.N64_C_UP)).isEqualTo(LogicalControl.BUTTON_X)
        assertThat(targetFor(p, CoreControlId.N64_C_LEFT)).isEqualTo(LogicalControl.BUTTON_LB)
        assertThat(targetFor(p, CoreControlId.N64_C_RIGHT)).isEqualTo(LogicalControl.BUTTON_RB)
        assertThat(targetFor(p, CoreControlId.Z)).isEqualTo(LogicalControl.BUTTON_LT)
    }

    @Test
    fun `mednafen_ngp swaps A and B RetroPad targets`() {
        val p = CoreControllerProfiles.byCoreId("mednafen_ngp")!!
        assertThat(targetFor(p, CoreControlId.BUTTON_A)).isEqualTo(LogicalControl.BUTTON_B)
        assertThat(targetFor(p, CoreControlId.BUTTON_B)).isEqualTo(LogicalControl.BUTTON_A)
        assertThat(targetFor(p, CoreControlId.OPTION)).isEqualTo(LogicalControl.BUTTON_START)
    }

    @Test
    fun `pcsx_rearmed maps PlayStation controls to their RetroPad targets`() {
        val p = CoreControllerProfiles.byCoreId("pcsx_rearmed")!!
        assertThat(targetFor(p, CoreControlId.BUTTON_B)).isEqualTo(LogicalControl.BUTTON_B)
        assertThat(targetFor(p, CoreControlId.BUTTON_A)).isEqualTo(LogicalControl.BUTTON_A)
        assertThat(targetFor(p, CoreControlId.BUTTON_X)).isEqualTo(LogicalControl.BUTTON_X)
        assertThat(targetFor(p, CoreControlId.L2)).isEqualTo(LogicalControl.BUTTON_LT)
        assertThat(targetFor(p, CoreControlId.R2)).isEqualTo(LogicalControl.BUTTON_RT)
        assertThat(targetFor(p, CoreControlId.LEFT_STICK_X)).isEqualTo(LogicalControl.AXIS_LX)
        assertThat(targetFor(p, CoreControlId.RIGHT_STICK_X)).isEqualTo(LogicalControl.AXIS_RX)
    }

    @Test
    fun `beetle_pce_fast maps TurboGrafx controls to their RetroPad targets`() {
        val p = CoreControllerProfiles.byCoreId("beetle_pce_fast")!!
        assertThat(targetFor(p, CoreControlId.BUTTON_I)).isEqualTo(LogicalControl.BUTTON_A)
        assertThat(targetFor(p, CoreControlId.BUTTON_II)).isEqualTo(LogicalControl.BUTTON_B)
        assertThat(targetFor(p, CoreControlId.BUTTON_III)).isEqualTo(LogicalControl.BUTTON_Y)
        assertThat(targetFor(p, CoreControlId.BUTTON_V)).isEqualTo(LogicalControl.BUTTON_LB)
        assertThat(targetFor(p, CoreControlId.BUTTON_VI)).isEqualTo(LogicalControl.BUTTON_RB)
    }

    @Test
    fun `stella maps Atari 2600 controls to their RetroPad targets`() {
        val p = CoreControllerProfiles.byCoreId("stella")!!
        assertThat(targetFor(p, CoreControlId.BUTTON_A)).isEqualTo(LogicalControl.BUTTON_A)
        assertThat(targetFor(p, CoreControlId.BUTTON_B)).isEqualTo(LogicalControl.BUTTON_B)
        assertThat(targetFor(p, CoreControlId.BUTTON_Y)).isEqualTo(LogicalControl.BUTTON_Y)
    }

    @Test
    fun `prosystem maps Atari 7800 controls to their RetroPad targets`() {
        val p = CoreControllerProfiles.byCoreId("prosystem")!!
        assertThat(targetFor(p, CoreControlId.BUTTON_1)).isEqualTo(LogicalControl.BUTTON_B)
        assertThat(targetFor(p, CoreControlId.BUTTON_2)).isEqualTo(LogicalControl.BUTTON_A)
        assertThat(targetFor(p, CoreControlId.PAUSE)).isEqualTo(LogicalControl.BUTTON_START)
        assertThat(targetFor(p, CoreControlId.SELECT)).isEqualTo(LogicalControl.BUTTON_SELECT)
    }

    // --- playerCount and consoleName match the plan table ---

    @Test
    fun `playerCount matches the plan table`() {
        val expected = mapOf(
            "genesis_plus_gx" to 2,
            "snes9x" to 2,
            "fceumm" to 2,
            "mgba" to 1,
            "stella" to 2,
            "gambatte" to 1,
            "beetle_pce_fast" to 2,
            "mednafen_ngp" to 1,
            "mednafen_wswan" to 1,
            "handy" to 1,
            "prosystem" to 2,
            "pcsx_rearmed" to 2,
            "mupen64plus_next" to 4,
        )
        for ((coreId, count) in expected) {
            val p = CoreControllerProfiles.byCoreId(coreId)
            assertThat(p).`as`("profile for %s", coreId).isNotNull
            assertThat(p!!.playerCount).`as`("playerCount for %s", coreId).isEqualTo(count)
        }
    }

    @Test
    fun `consoleName matches the plan table`() {
        val expected = mapOf(
            "genesis_plus_gx" to "Sega Systems",
            "snes9x" to "Super Nintendo",
            "fceumm" to "Nintendo Entertainment System",
            "mgba" to "Game Boy Advance",
            "stella" to "Atari 2600",
            "gambatte" to "Game Boy / Game Boy Color",
            "beetle_pce_fast" to "TurboGrafx-16",
            "mednafen_ngp" to "Neo Geo Pocket",
            "mednafen_wswan" to "WonderSwan",
            "handy" to "Atari Lynx",
            "prosystem" to "Atari 7800",
            "pcsx_rearmed" to "PlayStation",
            "mupen64plus_next" to "Nintendo 64",
        )
        for ((coreId, name) in expected) {
            val p = CoreControllerProfiles.byCoreId(coreId)
            assertThat(p).`as`("profile for %s", coreId).isNotNull
            assertThat(p!!.consoleName).`as`("consoleName for %s", coreId).isEqualTo(name)
        }
    }
}
