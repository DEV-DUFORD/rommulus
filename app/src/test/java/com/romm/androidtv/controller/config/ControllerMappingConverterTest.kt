package com.romm.androidtv.controller.config

import com.romm.androidtv.controller.model.AxisDirection
import com.romm.androidtv.controller.model.ControllerMapping
import com.romm.androidtv.controller.model.LogicalControl
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
        assertThat(mapping.buttons).containsEntry(97, LogicalControl.BUTTON_A)
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
        assertThat(mapping.axes).containsEntry(0, LogicalControl.AXIS_LX)
    }

    @Test
    fun `AxisDirection binding lands in axisDirections map`() {
        val result = config.toRouterMappings(profile)
        val mapping = result.getValue(0)
        // D_PAD_LEFT -> DPAD_LEFT, physical (axis=1, polarity=-1)
        assertThat(mapping.axisDirections)
            .containsEntry(AxisDirection(axis = 1, polarity = -1), LogicalControl.DPAD_LEFT)
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
            .containsEntry(android.view.KeyEvent.KEYCODE_DPAD_UP, LogicalControl.DPAD_UP)
        assertThat(mapping.axisDirections)
            .containsEntry(
                AxisDirection(android.view.MotionEvent.AXIS_Y, -1),
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
            AxisDirection(android.view.MotionEvent.AXIS_LTRIGGER, 1),
            LogicalControl.BUTTON_LT,
        )
        val pressed = com.romm.androidtv.controller.model.GamepadSnapshot.fromPhysicalInput(
            emptySet(),
            mapOf(android.view.MotionEvent.AXIS_LTRIGGER to 0.9f),
            mapping,
        )
        val released = com.romm.androidtv.controller.model.GamepadSnapshot.fromPhysicalInput(
            emptySet(),
            mapOf(android.view.MotionEvent.AXIS_LTRIGGER to 0f),
            mapping,
        )
        assertThat(pressed.buttons[LogicalControl.BUTTON_LT.index]).isEqualTo(1f)
        assertThat(released.buttons[LogicalControl.BUTTON_LT.index]).isZero()
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
                    mapOf(CoreControlId.BUTTON_B to ControlBindings(primary = PhysicalBinding.Key(98))),
                ),
            ),
        )
        val result = multiConfig.toRouterMappings(profile)
        assertThat(result).containsOnlyKeys(0, 2)
        assertThat(result.getValue(0).buttons).containsEntry(97, LogicalControl.BUTTON_A)
        assertThat(result.getValue(2).buttons).containsEntry(98, LogicalControl.BUTTON_B)
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
