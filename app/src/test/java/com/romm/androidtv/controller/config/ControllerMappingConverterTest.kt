package com.romm.androidtv.controller.config

import com.romm.androidtv.controller.model.AxisDirection
import com.romm.androidtv.controller.model.ControllerMapping
import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.model.NeutralKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ControllerMappingConverter — CoreControllerConfig.toRouterMappings")
class ControllerMappingConverterTest {

    private val profile = CoreControllerProfiles.byCoreId("snes9x")!!

    // A minimal single-player config containing one of each binding kind.
    private val config = CoreControllerConfig(
        coreId = "snes9x",
        players = mapOf(
            0 to PlayerControllerConfig(
                bindings = mapOf(
                    CoreControlId.BUTTON_A to ControlBindings(primary = PhysicalBinding.Key(97)),
                    CoreControlId.LEFT_STICK_X to ControlBindings(primary = PhysicalBinding.Axis(0)),
                    CoreControlId.D_PAD_LEFT to ControlBindings(
                        primary = PhysicalBinding.AxisDirection(1, -1),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `Key binding lands in buttons map`() {
        val result = config.toRouterMappings(profile)
        val mapping = result.getValue(0)
        // snes9x maps BUTTON_A -> LogicalControl.BUTTON_A
        assertThat(mapping.buttons).containsEntry(NeutralKey.BUTTON_B, LogicalControl.BUTTON_A)
    }

    @Test
    fun `Axis binding lands in axes map`() {
        // snes9x has no analog sticks; use mupen64plus which declares LEFT_STICK_X.
        val stickProfile = CoreControllerProfiles.byCoreId("mupen64plus_next")!!
        val stickConfig = CoreControllerConfig(
            coreId = "mupen64plus_next",
            players = mapOf(
                0 to PlayerControllerConfig(
                    bindings = mapOf(
                        CoreControlId.LEFT_STICK_X to ControlBindings(
                            primary = PhysicalBinding.Axis(0),
                        ),
                    ),
                ),
            ),
        )
        val result = stickConfig.toRouterMappings(stickProfile)
        val mapping = result.getValue(0)
        // LEFT_STICK_X -> AXIS_LX
        assertThat(mapping.axes).containsEntry(NeutralAxis.X, LogicalControl.AXIS_LX)
    }

    @Test
    fun `AxisDirection binding lands in axisDirections map`() {
        val result = config.toRouterMappings(profile)
        val mapping = result.getValue(0)
        // D_PAD_LEFT -> DPAD_LEFT, physical (axis=1, polarity=-1)
        assertThat(mapping.axisDirections)
            .containsEntry(AxisDirection(NeutralAxis.Y, -1), LogicalControl.DPAD_LEFT)
    }

    @Test
    fun `primary and secondary bindings both map to the same logical control`() {
        val dualConfig = CoreControllerConfig(
            coreId = "snes9x",
            players = mapOf(
                0 to PlayerControllerConfig(
                    mapOf(
                        CoreControlId.D_PAD_UP to ControlBindings(
                            primary = PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_DPAD_UP),
                            secondary = PhysicalBinding.AxisDirection(
                                android.view.MotionEvent.AXIS_Y,
                                -1,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val mapping = dualConfig.toRouterMappings(profile).getValue(0)

        assertThat(mapping.buttons)
            .containsEntry(NeutralKey.DPAD_UP, LogicalControl.DPAD_UP)
        assertThat(mapping.axisDirections)
            .containsEntry(
                AxisDirection(NeutralAxis.Y, -1),
                LogicalControl.DPAD_UP,
            )
    }

    @Test
    fun `analog trigger bound to digital L2 converts to a positive axis direction`() {
        val playStationProfile = CoreControllerProfiles.byCoreId("pcsx_rearmed")!!
        val triggerConfig = CoreControllerConfig(
            coreId = "pcsx_rearmed",
            players = mapOf(
                0 to PlayerControllerConfig(
                    mapOf(
                        CoreControlId.L2 to ControlBindings(
                            primary = PhysicalBinding.Axis(
                                android.view.MotionEvent.AXIS_LTRIGGER,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val mapping = triggerConfig.toRouterMappings(playStationProfile).getValue(0)

        assertThat(mapping.axisDirections).containsEntry(
            AxisDirection(NeutralAxis.LTRIGGER, 1),
            LogicalControl.BUTTON_LT,
        )
        val pressed = com.romm.androidtv.controller.model.GamepadSnapshot.fromPhysicalInput(
            emptySet(),
            mapOf(NeutralAxis.LTRIGGER to 0.9f),
            mapping,
        )
        val released = com.romm.androidtv.controller.model.GamepadSnapshot.fromPhysicalInput(
            emptySet(),
            mapOf(NeutralAxis.LTRIGGER to 0f),
            mapping,
        )
        assertThat(pressed.buttons[LogicalControl.BUTTON_LT.index]).isEqualTo(1f)
        assertThat(released.buttons[LogicalControl.BUTTON_LT.index]).isZero()
    }

    @Test
    fun `GBA A can be mapped from an L2 key event`() {
        val gbaProfile = CoreControllerProfiles.byCoreId("mgba")!!
        val gbaConfig = CoreControllerConfig(
            coreId = "mgba",
            players = mapOf(
                0 to PlayerControllerConfig(
                    mapOf(
                        CoreControlId.BUTTON_A to ControlBindings(
                            primary = PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_L2),
                            secondary = PhysicalBinding.AxisDirection(
                                android.view.MotionEvent.AXIS_LTRIGGER,
                                1,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val mapping = gbaConfig.toRouterMappings(gbaProfile).getValue(0)
        val pressed = com.romm.androidtv.controller.model.GamepadSnapshot.fromPhysicalInput(
            pressedKeys = setOf(NeutralKey.BUTTON_L2),
            axisValues = emptyMap(),
            mapping = mapping,
        )

        assertThat(mapping.buttons)
            .containsEntry(NeutralKey.BUTTON_L2, LogicalControl.BUTTON_A)
        assertThat(pressed.buttons[LogicalControl.BUTTON_A.index]).isEqualTo(1f)

        val triggerPressed = com.romm.androidtv.controller.model.GamepadSnapshot.fromPhysicalInput(
            pressedKeys = emptySet(),
            axisValues = mapOf(NeutralAxis.LTRIGGER to 0.9f),
            mapping = mapping,
        )
        assertThat(triggerPressed.buttons[LogicalControl.BUTTON_A.index]).isEqualTo(1f)
    }

    @Test
    fun `N64 Z button and hat axes survive conversion`() {
        val n64Profile = CoreControllerProfiles.byCoreId("mupen64plus_next")!!
        val n64Config = CoreControllerConfig(
            coreId = "mupen64plus_next",
            players = mapOf(
                0 to PlayerControllerConfig(
                    mapOf(
                        CoreControlId.Z to ControlBindings(
                            primary = PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_Z),
                        ),
                        CoreControlId.D_PAD_LEFT to ControlBindings(
                            primary = PhysicalBinding.AxisDirection(
                                android.view.MotionEvent.AXIS_HAT_X,
                                -1,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val mapping = n64Config.toRouterMappings(n64Profile).getValue(0)

        assertThat(mapping.buttons)
            .containsEntry(NeutralKey.BUTTON_Z, LogicalControl.BUTTON_LT)
        assertThat(mapping.axisDirections)
            .containsEntry(
                AxisDirection(NeutralAxis.HAT_X, -1),
                LogicalControl.DPAD_LEFT,
            )
    }

    @Test
    fun `pause menu bindings stay out of core input and become a two-button shortcut`() {
        val config = CoreControllerConfig(
            coreId = "snes9x",
            players = mapOf(
                0 to PlayerControllerConfig(
                    mapOf(
                        CoreControlId.PAUSE_MENU to ControlBindings(
                            primary = PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_L2),
                            secondary = PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_R2),
                        ),
                    ),
                ),
            ),
        )

        val mapping = config.toRouterMappings(profile).getValue(0)

        assertThat(mapping.buttons).isEmpty()
        assertThat(mapping.pauseMenuCombination).isEqualTo(
            com.romm.androidtv.controller.model.PauseMenuCombination(
                com.romm.androidtv.controller.model.PhysicalControl.Key(
                    android.view.KeyEvent.KEYCODE_BUTTON_L2,
                ),
                com.romm.androidtv.controller.model.PhysicalControl.Key(
                    android.view.KeyEvent.KEYCODE_BUTTON_R2,
                ),
            ),
        )
    }

    @Test
    fun `player with no config is omitted`() {
        val result = config.toRouterMappings(profile)
        assertThat(result).doesNotContainKeys(1, 2, 3)
        // Only player 0 was configured.
        assertThat(result).containsOnlyKeys(0)
    }

    @Test
    fun `empty players map produces empty no-op result`() {
        val emptyConfig = CoreControllerConfig(coreId = "snes9x", players = emptyMap())
        assertThat(emptyConfig.toRouterMappings(profile)).isEmpty()
    }

    @Test
    fun `multiple players each produce a mapping`() {
        val multiConfig = CoreControllerConfig(
            coreId = "snes9x",
            players = mapOf(
                0 to PlayerControllerConfig(
                    mapOf(CoreControlId.BUTTON_A to ControlBindings(primary = PhysicalBinding.Key(97))),
                ),
                2 to PlayerControllerConfig(
                    mapOf(CoreControlId.BUTTON_B to ControlBindings(primary = PhysicalBinding.Key(99))),
                ),
            ),
        )
        val result = multiConfig.toRouterMappings(profile)
        assertThat(result).containsOnlyKeys(0, 2)
        assertThat(result.getValue(0).buttons).containsEntry(NeutralKey.BUTTON_B, LogicalControl.BUTTON_A)
        assertThat(result.getValue(2).buttons).containsEntry(NeutralKey.BUTTON_X, LogicalControl.BUTTON_B)
    }

    @Test
    fun `non-standard right stick axes are stored under their raw physical axis`() {
        val n64Profile = CoreControllerProfiles.byCoreId("mupen64plus_next")!!
        val stickConfig = CoreControllerConfig(
            coreId = "mupen64plus_next",
            players = mapOf(
                0 to PlayerControllerConfig(
                    mapOf(
                        CoreControlId.N64_C_RIGHT to ControlBindings(
                            primary = PhysicalBinding.AxisDirection(
                                android.view.MotionEvent.AXIS_Z,
                                1,
                            ),
                        ),
                        CoreControlId.N64_C_DOWN to ControlBindings(
                            primary = PhysicalBinding.AxisDirection(
                                android.view.MotionEvent.AXIS_RZ,
                                1,
                            ),
                        ),
                        CoreControlId.N64_C_LEFT to ControlBindings(
                            primary = PhysicalBinding.AxisDirection(
                                android.view.MotionEvent.AXIS_Z,
                                -1,
                            ),
                        ),
                        CoreControlId.N64_C_UP to ControlBindings(
                            primary = PhysicalBinding.AxisDirection(
                                android.view.MotionEvent.AXIS_RZ,
                                -1,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val mapping = stickConfig.toRouterMappings(n64Profile).getValue(0)

        // C-Right -> BUTTON_RB on (AXIS_Z,+1), C-Down -> BUTTON_A on (AXIS_RZ,+1).
        // These are distinct keys, matching how playback populates axisValues with
        // the raw physical axes (AXIS_Z / AXIS_RZ) — so both fire correctly.
        assertThat(mapping.axisDirections)
            .containsEntry(
                AxisDirection(NeutralAxis.Z, 1),
                LogicalControl.BUTTON_RB,
            )
        assertThat(mapping.axisDirections)
            .containsEntry(
                AxisDirection(NeutralAxis.RZ, 1),
                LogicalControl.BUTTON_A,
            )
        assertThat(mapping.axisDirections)
            .containsEntry(
                AxisDirection(NeutralAxis.Z, -1),
                LogicalControl.BUTTON_LB,
            )
        assertThat(mapping.axisDirections)
            .containsEntry(
                AxisDirection(NeutralAxis.RZ, -1),
                LogicalControl.BUTTON_X,
            )

        // All four directions are stored under distinct (axis, polarity) keys, so
        // C-Right and C-Down can never collide (the dominant-axis capture fix).
        assertThat(mapping.axisDirections).hasSize(4)
        // No canonical RX/RY keys are produced.
        assertThat(mapping.axisDirections.keys)
            .doesNotContain(AxisDirection(NeutralAxis.RX, 1))
        assertThat(mapping.axisDirections.keys)
            .doesNotContain(AxisDirection(NeutralAxis.RY, 1))
    }

    @Test
    fun `unknown control id is skipped`() {
        val unknownConfig = CoreControllerConfig(
            coreId = "snes9x",
            players = mapOf(
                0 to PlayerControllerConfig(
                    bindings = mapOf(
                        CoreControlId.Z to ControlBindings(primary = PhysicalBinding.Key(99)),
                    ),
                ),
            ),
        )
        val result = unknownConfig.toRouterMappings(profile)
        // snes9x has no Z trigger; the binding is dropped and no maps are populated.
        val mapping: ControllerMapping = result.getValue(0)
        assertThat(mapping.buttons).isEmpty()
        assertThat(mapping.axes).isEmpty()
        assertThat(mapping.axisDirections).isEmpty()
    }
}
