package com.romm.androidtv.controller

import com.romm.androidtv.controller.model.ControllerSlot
import com.romm.androidtv.controller.model.DeviceSignature
import com.romm.androidtv.controller.model.GamepadSnapshot
import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.controller.model.SlotConnectionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("LibretroPadMapper — GamepadSnapshot to Libretro RetroPad translation")
class LibretroPadMapperTest {

    private fun snapshotWithButton(logical: LogicalControl, pressed: Boolean = true): GamepadSnapshot =
        GamepadSnapshot.withButton(GamepadSnapshot.EMPTY, logical, pressed)

    private fun snapshotWithAxis(logical: LogicalControl, value: Float): GamepadSnapshot =
        GamepadSnapshot.withAxis(GamepadSnapshot.EMPTY, logical, value)

    @Nested
    @DisplayName("digital buttons")
    inner class DigitalButtons {
        @Test
        fun `neutral snapshot maps to zero mask and centered axes`() {
            val state = LibretroPadMapper.map(GamepadSnapshot.EMPTY)
            assertThat(state).isEqualTo(LibretroPadState.NEUTRAL)
        }

        @Test
        fun `face buttons map to their distinct Libretro bit positions, not copied indices`() {
            // Libretro RETRO_DEVICE_ID_JOYPAD_* values: B=0, Y=1, A=8, X=9.
            // GamepadSnapshot indices: A=0, B=1, X=2, Y=3. These must NOT match
            // 1:1 -- this test guards against a naive "copy the array index" bug.
            assertThat(LibretroPadMapper.map(snapshotWithButton(LogicalControl.BUTTON_A)).buttonsMask)
                .isEqualTo(1 shl 8)
            assertThat(LibretroPadMapper.map(snapshotWithButton(LogicalControl.BUTTON_B)).buttonsMask)
                .isEqualTo(1 shl 0)
            assertThat(LibretroPadMapper.map(snapshotWithButton(LogicalControl.BUTTON_X)).buttonsMask)
                .isEqualTo(1 shl 9)
            assertThat(LibretroPadMapper.map(snapshotWithButton(LogicalControl.BUTTON_Y)).buttonsMask)
                .isEqualTo(1 shl 1)
        }

        @Test
        fun `dpad maps to UP DOWN LEFT RIGHT bit positions`() {
            assertThat(LibretroPadMapper.map(snapshotWithButton(LogicalControl.DPAD_UP)).buttonsMask)
                .isEqualTo(1 shl 4)
            assertThat(LibretroPadMapper.map(snapshotWithButton(LogicalControl.DPAD_DOWN)).buttonsMask)
                .isEqualTo(1 shl 5)
            assertThat(LibretroPadMapper.map(snapshotWithButton(LogicalControl.DPAD_LEFT)).buttonsMask)
                .isEqualTo(1 shl 6)
            assertThat(LibretroPadMapper.map(snapshotWithButton(LogicalControl.DPAD_RIGHT)).buttonsMask)
                .isEqualTo(1 shl 7)
        }

        @Test
        fun `menu equivalents map to SELECT and START`() {
            assertThat(LibretroPadMapper.map(snapshotWithButton(LogicalControl.BUTTON_SELECT)).buttonsMask)
                .isEqualTo(1 shl 2)
            assertThat(LibretroPadMapper.map(snapshotWithButton(LogicalControl.BUTTON_START)).buttonsMask)
                .isEqualTo(1 shl 3)
        }

        @Test
        fun `shoulder buttons map to L and R, stick clicks map to L3 and R3`() {
            assertThat(LibretroPadMapper.map(snapshotWithButton(LogicalControl.BUTTON_LB)).buttonsMask)
                .isEqualTo(1 shl 10)
            assertThat(LibretroPadMapper.map(snapshotWithButton(LogicalControl.BUTTON_RB)).buttonsMask)
                .isEqualTo(1 shl 11)
            assertThat(LibretroPadMapper.map(snapshotWithButton(LogicalControl.BUTTON_L3)).buttonsMask)
                .isEqualTo(1 shl 14)
            assertThat(LibretroPadMapper.map(snapshotWithButton(LogicalControl.BUTTON_R3)).buttonsMask)
                .isEqualTo(1 shl 15)
        }

        @Test
        fun `multiple simultaneous buttons combine in the mask without interference`() {
            var snapshot = snapshotWithButton(LogicalControl.BUTTON_A)
            snapshot = GamepadSnapshot.withButton(snapshot, LogicalControl.BUTTON_START, true)
            snapshot = GamepadSnapshot.withButton(snapshot, LogicalControl.DPAD_UP, true)

            val mask = LibretroPadMapper.map(snapshot).buttonsMask
            assertThat(mask and (1 shl 8)).isNotZero() // A
            assertThat(mask and (1 shl 3)).isNotZero() // START
            assertThat(mask and (1 shl 4)).isNotZero() // UP
            assertThat(mask and (1 shl 1)).isZero() // Y not pressed
        }
    }

    @Nested
    @DisplayName("trigger buttons: digital-button and axis-as-trigger devices")
    inner class Triggers {
        @Test
        fun `digital trigger button maps to L2 and R2`() {
            assertThat(LibretroPadMapper.map(snapshotWithButton(LogicalControl.BUTTON_LT)).buttonsMask)
                .isEqualTo(1 shl 12)
            assertThat(LibretroPadMapper.map(snapshotWithButton(LogicalControl.BUTTON_RT)).buttonsMask)
                .isEqualTo(1 shl 13)
        }

        @Test
        fun `trigger exposed as an axis also maps to L2 and R2 past the press threshold`() {
            val left = LibretroPadMapper.map(snapshotWithAxis(LogicalControl.TRIGGER_LEFT, 0.9f))
            val right = LibretroPadMapper.map(snapshotWithAxis(LogicalControl.TRIGGER_RIGHT, 0.9f))
            assertThat(left.buttonsMask).isEqualTo(1 shl 12)
            assertThat(right.buttonsMask).isEqualTo(1 shl 13)
        }

        @Test
        fun `trigger axis below the press threshold does not register as a button`() {
            val state = LibretroPadMapper.map(snapshotWithAxis(LogicalControl.TRIGGER_LEFT, 0.2f))
            assertThat(state.buttonsMask).isZero()
        }
    }

    @Nested
    @DisplayName("analog sticks")
    inner class AnalogSticks {
        @Test
        fun `left and right stick axes map independently, not by copied index`() {
            // GamepadSnapshot axis indices: LX=0, LY=1, RX=2, RY=3.
            // A naive "copy axis index into Libretro index" bug would still
            // pass a same-stick-only test, so assert both sticks in one
            // snapshot to guard against left/right cross-talk.
            var snapshot = snapshotWithAxis(LogicalControl.AXIS_LX, 1.0f)
            snapshot = GamepadSnapshot.withAxis(snapshot, LogicalControl.AXIS_RY, -1.0f)

            val state = LibretroPadMapper.map(snapshot)
            assertThat(state.leftX).isEqualTo(32767)
            assertThat(state.leftY).isEqualTo(0)
            assertThat(state.rightX).isEqualTo(0)
            assertThat(state.rightY).isEqualTo(-32767)
        }

        @Test
        fun `full deflection clamps to signed 16-bit Libretro range`() {
            val positive = LibretroPadMapper.map(snapshotWithAxis(LogicalControl.AXIS_LX, 1.0f))
            val negative = LibretroPadMapper.map(snapshotWithAxis(LogicalControl.AXIS_LX, -1.0f))
            assertThat(positive.leftX).isEqualTo(32767)
            assertThat(negative.leftX).isEqualTo(-32767)
        }

        @Test
        fun `centered stick maps to zero`() {
            val state = LibretroPadMapper.map(snapshotWithAxis(LogicalControl.AXIS_LX, 0.0f))
            assertThat(state.leftX).isZero()
        }

        @Test
        fun `Y axis sign is passed through unmodified (already correct convention, no double-invert)`() {
            // Up is negative in both the existing GamepadSnapshot convention
            // and Libretro's RETRO_DEVICE_ID_ANALOG_Y -- this must NOT flip sign.
            val up = LibretroPadMapper.map(snapshotWithAxis(LogicalControl.AXIS_LY, -0.5f))
            val down = LibretroPadMapper.map(snapshotWithAxis(LogicalControl.AXIS_LY, 0.5f))
            assertThat(up.leftY).isLessThan(0)
            assertThat(down.leftY).isGreaterThan(0)
        }
    }

    @Nested
    @DisplayName("mapControllerSlotsToLibretroPorts")
    inner class PortMapping {
        @Test
        fun `four slots map to four ports in player-number order`() {
            val slots = ControllerSlot.createAllSlots().mapIndexed { i, slot ->
                if (i == 0) {
                    slot.copy(
                        connectionState = SlotConnectionState.CONNECTED,
                        currentSnapshot = snapshotWithButton(LogicalControl.BUTTON_START)
                    )
                } else {
                    slot
                }
            }

            val ports = mapControllerSlotsToLibretroPorts(slots)

            assertThat(ports).hasSize(4)
            assertThat(ports[0].buttonsMask).isEqualTo(1 shl 3) // START, port 0 (player 1)
            assertThat(ports[1]).isEqualTo(LibretroPadState.NEUTRAL)
            assertThat(ports[2]).isEqualTo(LibretroPadState.NEUTRAL)
            assertThat(ports[3]).isEqualTo(LibretroPadState.NEUTRAL)
        }

        @Test
        fun `a missing slot for a player number maps to NEUTRAL rather than throwing`() {
            val onlyPlayerTwo = listOf(ControllerSlot.createAllSlots()[1])
            val ports = mapControllerSlotsToLibretroPorts(onlyPlayerTwo)

            assertThat(ports).hasSize(4)
            assertThat(ports).allMatch { it == LibretroPadState.NEUTRAL }
        }

        @Test
        fun `physical controller is compacted to port zero ahead of Android TV virtual gamepads`() {
            val slots = ControllerSlot.createAllSlots().toMutableList()
            slots[0] = slots[0].assign(
                DeviceSignature("virtual-search", 0x18d1, 0x0100, "virtual-search")
            )
            slots[1] = slots[1].assign(
                DeviceSignature("virtual-remote", 0x18d1, 0x0100, "virtual-remote")
            )
            slots[2] = slots[2].assign(
                DeviceSignature("xbox", 0x045e, 0x0b13, "Xbox Wireless Controller")
            ).updateSnapshot(snapshotWithButton(LogicalControl.BUTTON_A))

            val ports = mapControllerSlotsToLibretroPorts(slots)

            assertThat(ports[0].buttonsMask).isEqualTo(1 shl 8)
            assertThat(ports[1]).isEqualTo(LibretroPadState.NEUTRAL)
            assertThat(ports[2]).isEqualTo(LibretroPadState.NEUTRAL)
        }

        @Test
        fun `multiple physical controllers retain their relative slot order`() {
            val slots = ControllerSlot.createAllSlots().toMutableList()
            slots[0] = slots[0].assign(DeviceSignature.VIRTUAL_REMOTE)
            slots[1] = slots[1].assign(
                DeviceSignature("first", 1, 1, "First Controller")
            ).updateSnapshot(snapshotWithButton(LogicalControl.BUTTON_A))
            slots[3] = slots[3].assign(
                DeviceSignature("second", 2, 2, "Second Controller")
            ).updateSnapshot(snapshotWithButton(LogicalControl.BUTTON_B))

            val ports = mapControllerSlotsToLibretroPorts(slots)

            assertThat(ports[0].buttonsMask).isEqualTo(1 shl 8)
            assertThat(ports[1].buttonsMask).isEqualTo(1 shl 0)
        }
    }
}
