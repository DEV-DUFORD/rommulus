package com.romm.androidtv.controller

import android.view.MotionEvent
import com.romm.androidtv.controller.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("Controller model — mapping, axes, deadzones, inversion, triggers, hat switches")
class ControllerModelTest {

    @Nested
    @DisplayName("AxisConfig — deadzone and inversion")
    inner class AxisConfigTests {
        @Test
        @DisplayName("deadzone zeroes small values")
        fun `deadzone zeroes small values`() {
            val config = AxisConfig(deadzone = 0.2f, inverted = false)
            assertThat(config.apply(0.1f)).isZero()
            assertThat(config.apply(-0.1f)).isZero()
            assertThat(config.apply(0.0f)).isZero()
        }

        @Test
        @DisplayName("deadzone preserves values outside range")
        fun `deadzone preserves large values`() {
            val config = AxisConfig(deadzone = 0.2f, inverted = false)
            assertThat(config.apply(0.5f)).isEqualTo(0.5f)
            assertThat(config.apply(-0.8f)).isEqualTo(-0.8f)
        }

        @Test
        @DisplayName("deadzone at boundary")
        fun `deadzone at exact boundary`() {
            val config = AxisConfig(deadzone = 0.2f, inverted = false)
            assertThat(config.apply(0.2f)).isEqualTo(0.2f)
            assertThat(config.apply(-0.2f)).isEqualTo(-0.2f)
            assertThat(config.apply(0.1999f)).isZero()
            assertThat(config.apply(0.2001f)).isEqualTo(0.2001f)
        }

        @Test
        @DisplayName("inversion flips sign")
        fun `inversion flips sign`() {
            val config = AxisConfig(deadzone = 0.0f, inverted = true)
            assertThat(config.apply(0.5f)).isEqualTo(-0.5f)
            assertThat(config.apply(-0.3f)).isEqualTo(0.3f)
        }

        @Test
        @DisplayName("deadzone + inversion combined")
        fun `deadzone and inversion combined`() {
            val config = AxisConfig(deadzone = 0.15f, inverted = true)
            assertThat(config.apply(0.1f)).isZero()
            assertThat(config.apply(0.5f)).isEqualTo(-0.5f)
        }

        @Test
        @DisplayName("zero deadzone passes all values through")
        fun `zero deadzone`() {
            val config = AxisConfig(deadzone = 0f, inverted = false)
            assertThat(config.apply(0.001f)).isEqualTo(0.001f)
            assertThat(config.apply(-0.001f)).isEqualTo(-0.001f)
        }

        @Test
        @DisplayName("clamps values to [-1, +1]")
        fun `clamps out of range`() {
            val config = AxisConfig()
            assertThat(config.apply(2.0f)).isEqualTo(1f)
            assertThat(config.apply(-3.0f)).isEqualTo(-1f)
        }

        @Test
        @DisplayName("rejects invalid deadzone")
        fun `rejects negative deadzone`() {
            assertThrows<IllegalArgumentException> {
                AxisConfig(deadzone = -0.1f)
            }
        }

        @Test
        @DisplayName("DEFAULT has 0.15 deadzone, no inversion")
        fun `default values`() {
            val default = AxisConfig.DEFAULT
            assertThat(default.deadzone).isEqualTo(0.15f)
            assertThat(default.inverted).isFalse()
        }
    }

    @Nested
    @DisplayName("ControllerMapping — button/axis mappings and A/B swap")
    inner class ControllerMappingTests {
        @Test
        @DisplayName("default mapping contains all standard buttons")
        fun `default has all buttons`() {
            val mapping = ControllerMapping()
            assertThat(mapping.buttons.size).isGreaterThan(10)
            assertThat(mapping.buttons).containsKey(android.view.KeyEvent.KEYCODE_BUTTON_A)
            assertThat(mapping.buttons).containsKey(android.view.KeyEvent.KEYCODE_BUTTON_B)
        }

        @Test
        @DisplayName("default axis mapping covers LX, LY, RX, RY")
        fun `default has all axes`() {
            val mapping = ControllerMapping()
            assertThat(mapping.axes.size).isGreaterThan(3)
            assertThat(mapping.axes).containsKey(android.view.MotionEvent.AXIS_X)
        }

        @Test
        @DisplayName("getAxisConfig returns DEFAULT for unmapped axis")
        fun `unmapped axis returns default config`() {
            val mapping = ControllerMapping()
            val config = mapping.getAxisConfig(LogicalControl.AXIS_LX)
            assertThat(config.deadzone).isEqualTo(0.15f)
        }

        @Test
        @DisplayName("getAxisConfig returns custom config when set")
        fun `custom axis config`() {
            val custom = AxisConfig(deadzone = 0.3f, inverted = true)
            val mapping = ControllerMapping(
                axisConfigs = mapOf(LogicalControl.AXIS_LX to custom)
            )
            val config = mapping.getAxisConfig(LogicalControl.AXIS_LX)
            assertThat(config.deadzone).isEqualTo(0.3f)
            assertThat(config.inverted).isTrue()
        }

        @Test
        @DisplayName("swapAB exchanges A and B button assignments")
        fun `swap AB buttons`() {
            val mapping = ControllerMapping()
            val swapped = ControllerMapping.swapAB(mapping)

            val aKeys = mapping.buttons.filterValues { it == LogicalControl.BUTTON_A }.keys
            val bKeys = mapping.buttons.filterValues { it == LogicalControl.BUTTON_B }.keys

            for (key in aKeys) {
                assertThat(swapped.buttons[key]).isEqualTo(LogicalControl.BUTTON_B)
            }
            for (key in bKeys) {
                assertThat(swapped.buttons[key]).isEqualTo(LogicalControl.BUTTON_A)
            }

            val otherKeys = mapping.buttons.keys - aKeys - bKeys
            for (key in otherKeys) {
                assertThat(swapped.buttons[key]).isEqualTo(mapping.buttons[key])
            }
        }

        @Test
        @DisplayName("double swapAB restores original mapping")
        fun `double swap restores original`() {
            val mapping = ControllerMapping()
            val swappedOnce = ControllerMapping.swapAB(mapping)
            val swappedTwice = ControllerMapping.swapAB(swappedOnce)
            assertThat(swappedTwice.buttons).isEqualTo(mapping.buttons)
        }

        @Test
        @DisplayName("custom mapping with A/B swap preserves custom axes")
        fun `swap preserves axes`() {
            val mapping = ControllerMapping(
                buttons = mapOf(
                    android.view.KeyEvent.KEYCODE_BUTTON_A to LogicalControl.BUTTON_B,
                    android.view.KeyEvent.KEYCODE_BUTTON_B to LogicalControl.BUTTON_A
                ),
                axes = mapOf(
                    android.view.MotionEvent.AXIS_X to LogicalControl.AXIS_RX,
                    android.view.MotionEvent.AXIS_Y to LogicalControl.AXIS_RY
                )
            )
            val swapped = ControllerMapping.swapAB(mapping)
            assertThat(swapped.axes).isEqualTo(mapping.axes)
        }
    }

    @Nested
    @DisplayName("GamepadSnapshot — immutable state and incremental updates")
    inner class GamepadSnapshotTests {
        @Test
        @DisplayName("EMPTY snapshot has all zeros")
        fun `empty snapshot`() {
            val snap = GamepadSnapshot.EMPTY
            assertThat(snap.buttons).hasSize(16)
            assertThat(snap.axes).hasSize(6)
            snap.buttons.forEach { assertThat(it).isZero() }
            snap.axes.forEach { assertThat(it).isZero() }
        }

        @Test
        @DisplayName("fromPhysicalInput maps pressed keys to buttons")
        fun `physical input to snapshot`() {
            val mapping = ControllerMapping()
            val pressed = setOf(android.view.KeyEvent.KEYCODE_BUTTON_A)
            val snap = GamepadSnapshot.fromPhysicalInput(pressed, emptyMap(), mapping)

            assertThat(snap.buttons[LogicalControl.BUTTON_A.index]).isEqualTo(1.0f)
            assertThat(snap.buttons[LogicalControl.BUTTON_B.index]).isZero()
        }

        @Test
        @DisplayName("fromPhysicalInput maps axis values with deadzone")
        fun `physical axes apply deadzone`() {
            val mapping = ControllerMapping()
            val axes = mapOf(
                android.view.MotionEvent.AXIS_X to 0.1f,
                android.view.MotionEvent.AXIS_Y to 0.5f
            )
            val snap = GamepadSnapshot.fromPhysicalInput(emptySet(), axes, mapping)

            assertThat(snap.axes[LogicalControl.AXIS_LX.index]).isZero()
            assertThat(snap.axes[LogicalControl.AXIS_LY.index]).isEqualTo(0.5f)
        }

        @Test
        @DisplayName("fromPhysicalInput with inverted axis")
        fun `inverted axis`() {
            val mapping = ControllerMapping(
                axisConfigs = mapOf(
                    LogicalControl.AXIS_LX to AxisConfig(deadzone = 0f, inverted = true)
                )
            )
            val axes = mapOf(android.view.MotionEvent.AXIS_X to 0.5f)
            val snap = GamepadSnapshot.fromPhysicalInput(emptySet(), axes, mapping)
            assertThat(snap.axes[LogicalControl.AXIS_LX.index]).isEqualTo(-0.5f)
        }

        @Test
        @DisplayName("withButton toggles a single button immutably")
        fun `with button press`() {
            val base = GamepadSnapshot.EMPTY
            val pressed = GamepadSnapshot.withButton(base, LogicalControl.BUTTON_A, true)
            val released = GamepadSnapshot.withButton(pressed, LogicalControl.BUTTON_A, false)

            assertThat(pressed.buttons[0]).isEqualTo(1.0f)
            assertThat(released.buttons[0]).isZero()
            assertThat(base.buttons[0]).isZero()
        }

        @Test
        @DisplayName("withAxis sets a single axis immutably")
        fun `with axis`() {
            val base = GamepadSnapshot.EMPTY
            val moved = GamepadSnapshot.withAxis(base, LogicalControl.AXIS_LX, 0.75f)
            assertThat(moved.axes[0]).isEqualTo(0.75f)
            assertThat(base.axes[0]).isZero()
        }

        @Test
        @DisplayName("withButton ignores axis controls")
        fun `with button ignores axis type`() {
            val base = GamepadSnapshot.EMPTY
            val result = GamepadSnapshot.withButton(
                base, LogicalControl.AXIS_LX, true
            )
            assertThat(result).isSameAs(base)
        }

        @Test
        @DisplayName("withAxis clamps to [-1, +1]")
        fun `axis clamped`() {
            val snap = GamepadSnapshot.withAxis(
                GamepadSnapshot.EMPTY, LogicalControl.AXIS_LX, 2.0f
            )
            assertThat(snap.axes[0]).isEqualTo(1.0f)
        }

        @Test
        @DisplayName("isAnyButtonPressed returns true when button active")
        fun `any button pressed`() {
            val snap = GamepadSnapshot.withButton(
                GamepadSnapshot.EMPTY, LogicalControl.BUTTON_A, true
            )
            assertThat(snap.isAnyButtonPressed).isTrue()
            assertThat(GamepadSnapshot.EMPTY.isAnyButtonPressed).isFalse()
        }

        @Test
        @DisplayName("isAnyAxisActive returns true when axis active")
        fun `any axis active`() {
            val snap = GamepadSnapshot.withAxis(
                GamepadSnapshot.EMPTY, LogicalControl.AXIS_LX, 0.5f
            )
            assertThat(snap.isAnyAxisActive).isTrue()
            assertThat(GamepadSnapshot.EMPTY.isAnyAxisActive).isFalse()
        }

        @Test
        @DisplayName("rejects wrong button count")
        fun `rejects wrong button size`() {
            assertThrows<IllegalArgumentException> {
                GamepadSnapshot(FloatArray(10), FloatArray(6))
            }
        }

        @Test
        @DisplayName("rejects wrong axis count")
        fun `rejects wrong axis size`() {
            assertThrows<IllegalArgumentException> {
                GamepadSnapshot(FloatArray(16), FloatArray(4))
            }
        }

        @Test
        @DisplayName("triggers map to axis indices 4 and 5")
        fun `trigger axis mapping`() {
            val mapping = ControllerMapping()
            val axes = mapOf(
                android.view.MotionEvent.AXIS_GAS to 0.8f,
                android.view.MotionEvent.AXIS_BRAKE to 0.3f
            )
            val snap = GamepadSnapshot.fromPhysicalInput(emptySet(), axes, mapping)
            assertThat(snap.axes[LogicalControl.TRIGGER_RIGHT.index]).isEqualTo(0.8f)
            assertThat(snap.axes[LogicalControl.TRIGGER_LEFT.index]).isEqualTo(0.3f)
        }

        @Test
        @DisplayName("D-pad hat values map to buttons 12-15")
        fun `hat to dpad buttons`() {
            val mapping = ControllerMapping()
            val pressed = setOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            val snap = GamepadSnapshot.fromPhysicalInput(pressed, emptyMap(), mapping)
            assertThat(snap.buttons[LogicalControl.DPAD_UP.index]).isEqualTo(1.0f)
            assertThat(snap.buttons[LogicalControl.DPAD_RIGHT.index]).isEqualTo(1.0f)
            assertThat(snap.buttons[LogicalControl.DPAD_DOWN.index]).isZero()
        }
    }

    @Nested
    @DisplayName("ControllerSlot — assignment, disconnect, reconnect")
    inner class ControllerSlotTests {
        @Test
        @DisplayName("assign sets signature and CONNECTED state")
        fun `assign device`() {
            val slot = ControllerSlot(playerNumber = 1)
            val sig = DeviceSignature("test-sig", 0x045e, 0x02e0, "Xbox Controller")
            val assigned = slot.assign(sig)

            assertThat(assigned.preferredSignature).isEqualTo(sig)
            assertThat(assigned.connectionState).isEqualTo(SlotConnectionState.CONNECTED)
            assertThat(assigned.isActive).isTrue()
        }

        @Test
        @DisplayName("disconnect preserves mapping and signature")
        fun `disconnect preserves mapping`() {
            val slot = ControllerSlot(playerNumber = 2)
                .assign(DeviceSignature("sig", 1, 2, "Test"))
            val disconnected = slot.disconnect()

            assertThat(disconnected.connectionState).isEqualTo(SlotConnectionState.DISCONNECTED)
            assertThat(disconnected.preferredSignature).isEqualTo(slot.preferredSignature)
            assertThat(disconnected.mapping).isEqualTo(slot.mapping)
            assertThat(disconnected.isActive).isFalse()
        }

        @Test
        @DisplayName("disconnect clears snapshot")
        fun `disconnect clears snapshot`() {
            val slot = ControllerSlot(playerNumber = 1)
                .assign(DeviceSignature("sig", 1, 2, "Test"))
            val withInput = slot.updateSnapshot(
                GamepadSnapshot.withButton(GamepadSnapshot.EMPTY, LogicalControl.BUTTON_A, true)
            )
            val disconnected = withInput.disconnect()
            assertThat(disconnected.currentSnapshot).isEqualTo(GamepadSnapshot.EMPTY)
        }

        @Test
        @DisplayName("reconnect restores CONNECTED state")
        fun `reconnect`() {
            val slot = ControllerSlot(playerNumber = 3)
                .assign(DeviceSignature("sig", 1, 2, "Test"))
                .disconnect()
            val reconnected = slot.reconnect()
            assertThat(reconnected.connectionState).isEqualTo(SlotConnectionState.CONNECTED)
            assertThat(reconnected.isActive).isTrue()
        }

        @Test
        @DisplayName("updateSnapshot replaces immutably")
        fun `update snapshot immutably`() {
            val slot = ControllerSlot(playerNumber = 1)
            val snap1 = GamepadSnapshot.withButton(GamepadSnapshot.EMPTY, LogicalControl.BUTTON_A, true)
            val snap2 = GamepadSnapshot.withButton(GamepadSnapshot.EMPTY, LogicalControl.BUTTON_B, true)

            val s1 = slot.updateSnapshot(snap1)
            val s2 = s1.updateSnapshot(snap2)

            assertThat(s1.currentSnapshot).isEqualTo(snap1)
            assertThat(s2.currentSnapshot).isEqualTo(snap2)
            assertThat(slot.currentSnapshot).isEqualTo(GamepadSnapshot.EMPTY)
        }

        @Test
        @DisplayName("remap replaces mapping immediately")
        fun `remap immediately`() {
            val slot = ControllerSlot(playerNumber = 1)
            val newMapping = ControllerMapping.swapAB(slot.mapping)
            val remapped = slot.remap(newMapping)
            assertThat(remapped.mapping).isEqualTo(newMapping)
            assertThat(slot.mapping).isNotEqualTo(newMapping)
        }

        @Test
        @DisplayName("createAllSlots produces four slots numbered 1-4 (W3C browser contract)")
        fun `create all slots`() {
            val slots = ControllerSlot.createAllSlots()
            assertThat(slots).hasSize(4)
            assertThat(slots.map { it.playerNumber }).containsExactly(1, 2, 3, 4)
            slots.forEach {
                assertThat(it.connectionState).isEqualTo(SlotConnectionState.UNASSIGNED)
                assertThat(it.preferredSignature).isNull()
            }
        }

        @Test
        @DisplayName("rejects invalid player number")
        fun `rejects player 0`() {
            assertThrows<IllegalArgumentException> { ControllerSlot(playerNumber = 0) }
        }

        @Test
        @DisplayName("rejects player number > 5")
        fun `rejects player 6`() {
            assertThrows<IllegalArgumentException> { ControllerSlot(playerNumber = 6) }
        }
    }

    @Nested
    @DisplayName("LogicalControl — standard gamepad layout")
    inner class LogicalControlTests {
        @Test
        @DisplayName("buttons have correct GamepadAPI indices")
        fun `button indices`() {
            assertThat(LogicalControl.BUTTON_A.index).isEqualTo(0)
            assertThat(LogicalControl.BUTTON_B.index).isEqualTo(1)
            assertThat(LogicalControl.BUTTON_X.index).isEqualTo(2)
            assertThat(LogicalControl.BUTTON_Y.index).isEqualTo(3)
            assertThat(LogicalControl.DPAD_UP.index).isEqualTo(12)
        }

        @Test
        @DisplayName("axes have correct indices")
        fun `axis indices`() {
            assertThat(LogicalControl.AXIS_LX.index).isEqualTo(0)
            assertThat(LogicalControl.AXIS_LY.index).isEqualTo(1)
            assertThat(LogicalControl.AXIS_RX.index).isEqualTo(2)
            assertThat(LogicalControl.AXIS_RY.index).isEqualTo(3)
        }

        @Test
        @DisplayName("KEYCODE_TO_CONTROL maps standard key codes")
        fun `keycode mapping`() {
            assertThat(KEYCODE_TO_CONTROL[android.view.KeyEvent.KEYCODE_BUTTON_A])
                .isEqualTo(LogicalControl.BUTTON_A)
            assertThat(KEYCODE_TO_CONTROL[android.view.KeyEvent.KEYCODE_DPAD_UP])
                .isEqualTo(LogicalControl.DPAD_UP)
        }

        @Test
        @DisplayName("AXIS_TO_CONTROL maps standard axes")
        fun `axis mapping`() {
            assertThat(AXIS_TO_CONTROL[android.view.MotionEvent.AXIS_X])
                .isEqualTo(LogicalControl.AXIS_LX)
            assertThat(AXIS_TO_CONTROL[android.view.MotionEvent.AXIS_GAS])
                .isEqualTo(LogicalControl.TRIGGER_RIGHT)
        }
    }

    @Nested
    @DisplayName("DeviceSignature — reconnect identity")
    inner class DeviceSignatureTests {
        @Test
        @DisplayName("identical VID/PID/sources produce matching reconnect keys")
        fun `matching reconnect keys`() {
            val sig1 = DeviceSignature("vid:045e-pid:02e0-src:1689", 0x045e, 0x02e0, "Xbox One")
            val sig2 = DeviceSignature("vid:045e-pid:02e0-src:1689", 0x045e, 0x02e0, "Xbox Series")
            assertThat(sig1.matchesReconnect(sig2)).isTrue()
        }

        @Test
        @DisplayName("different VID/PID produce non-matching reconnect keys")
        fun `non matching reconnect keys`() {
            val sig1 = DeviceSignature("vid:045e-pid:02e0-src:1689", 0x045e, 0x02e0, "Xbox")
            val sig2 = DeviceSignature("vid:054c-pid:0ce6-src:1689", 0x054c, 0x0ce6, "DualShock")
            assertThat(sig1.matchesReconnect(sig2)).isFalse()
        }

        @Test
        @DisplayName("full data equality still requires same name")
        fun `full equality requires same name`() {
            val sig1 = DeviceSignature("vid:045e-pid:02e0-src:1689", 0x045e, 0x02e0, "Xbox One")
            val sig2 = DeviceSignature("vid:045e-pid:02e0-src:1689", 0x045e, 0x02e0, "Xbox Series")
            assertThat(sig1).isNotEqualTo(sig2)
        }
    }

    @Nested
    @DisplayName("GamepadSnapshot — angle-based d-pad mapping")
    inner class AngleBasedDpadTests {
        private val dpadMapping = ControllerMapping(
            axisDirections = mapOf(
                AxisDirection(MotionEvent.AXIS_X,  1) to LogicalControl.DPAD_RIGHT,
                AxisDirection(MotionEvent.AXIS_X, -1) to LogicalControl.DPAD_LEFT,
                AxisDirection(MotionEvent.AXIS_Y,  1) to LogicalControl.DPAD_DOWN,
                AxisDirection(MotionEvent.AXIS_Y, -1) to LogicalControl.DPAD_UP
            )
        )

        private fun snap(axisX: Float = 0f, axisY: Float = 0f) =
            GamepadSnapshot.fromPhysicalInput(
                emptySet(),
                mapOf(MotionEvent.AXIS_X to axisX, MotionEvent.AXIS_Y to axisY),
                dpadMapping
            )

        private fun snap(mapping: ControllerMapping, axisX: Float = 0f, axisY: Float = 0f) =
            GamepadSnapshot.fromPhysicalInput(
                emptySet(),
                mapOf(MotionEvent.AXIS_X to axisX, MotionEvent.AXIS_Y to axisY),
                mapping
            )

        // ── Cardinal directions ────────────────────────────────────────────────

        @Test
        @DisplayName("pure right: only RIGHT fires")
        fun `pure right`() {
            val s = snap(axisX = 1.0f, axisY = 0f)
            assertThat(s.buttons[LogicalControl.DPAD_RIGHT.index]).isEqualTo(1.0f)
            assertThat(s.buttons[LogicalControl.DPAD_UP.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_DOWN.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_LEFT.index]).isZero()
        }

        @Test
        @DisplayName("pure left: only LEFT fires")
        fun `pure left`() {
            val s = snap(axisX = -1.0f, axisY = 0f)
            assertThat(s.buttons[LogicalControl.DPAD_LEFT.index]).isEqualTo(1.0f)
            assertThat(s.buttons[LogicalControl.DPAD_UP.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_DOWN.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_RIGHT.index]).isZero()
        }

        @Test
        @DisplayName("pure up: only UP fires")
        fun `pure up`() {
            val s = snap(axisX = 0f, axisY = -1.0f)
            assertThat(s.buttons[LogicalControl.DPAD_UP.index]).isEqualTo(1.0f)
            assertThat(s.buttons[LogicalControl.DPAD_DOWN.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_LEFT.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_RIGHT.index]).isZero()
        }

        @Test
        @DisplayName("pure down: only DOWN fires")
        fun `pure down`() {
            val s = snap(axisX = 0f, axisY = 1.0f)
            assertThat(s.buttons[LogicalControl.DPAD_DOWN.index]).isEqualTo(1.0f)
            assertThat(s.buttons[LogicalControl.DPAD_UP.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_LEFT.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_RIGHT.index]).isZero()
        }

        // ── Diagonal directions ────────────────────────────────────────────────

        @Test
        @DisplayName("up-right diagonal: UP + RIGHT fire")
        fun `up right diagonal`() {
            val s = snap(axisX = 0.7071f, axisY = -0.7071f) // ~45°
            assertThat(s.buttons[LogicalControl.DPAD_UP.index]).isEqualTo(1.0f)
            assertThat(s.buttons[LogicalControl.DPAD_RIGHT.index]).isEqualTo(1.0f)
            assertThat(s.buttons[LogicalControl.DPAD_DOWN.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_LEFT.index]).isZero()
        }

        @Test
        @DisplayName("up-left diagonal: UP + LEFT fire")
        fun `up left diagonal`() {
            val s = snap(axisX = -0.7071f, axisY = -0.7071f) // ~135°
            assertThat(s.buttons[LogicalControl.DPAD_UP.index]).isEqualTo(1.0f)
            assertThat(s.buttons[LogicalControl.DPAD_LEFT.index]).isEqualTo(1.0f)
            assertThat(s.buttons[LogicalControl.DPAD_DOWN.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_RIGHT.index]).isZero()
        }

        @Test
        @DisplayName("down-left diagonal: DOWN + LEFT fire")
        fun `down left diagonal`() {
            val s = snap(axisX = -0.7071f, axisY = 0.7071f) // ~225°
            assertThat(s.buttons[LogicalControl.DPAD_DOWN.index]).isEqualTo(1.0f)
            assertThat(s.buttons[LogicalControl.DPAD_LEFT.index]).isEqualTo(1.0f)
            assertThat(s.buttons[LogicalControl.DPAD_UP.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_RIGHT.index]).isZero()
        }

        @Test
        @DisplayName("down-right diagonal: DOWN + RIGHT fire")
        fun `down right diagonal`() {
            val s = snap(axisX = 0.7071f, axisY = 0.7071f) // ~315°
            assertThat(s.buttons[LogicalControl.DPAD_DOWN.index]).isEqualTo(1.0f)
            assertThat(s.buttons[LogicalControl.DPAD_RIGHT.index]).isEqualTo(1.0f)
            assertThat(s.buttons[LogicalControl.DPAD_UP.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_LEFT.index]).isZero()
        }

        // ── Deadzone ───────────────────────────────────────────────────────────

        @Test
        @DisplayName("within deadzone: no d-pad button fires")
        fun `within deadzone`() {
            val s = snap(axisX = 0.1f, axisY = 0.1f) // both < 0.15 default deadzone
            assertThat(s.buttons[LogicalControl.DPAD_UP.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_DOWN.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_LEFT.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_RIGHT.index]).isZero()
        }

        @Test
        @DisplayName("zero axes: no d-pad button fires")
        fun `zero axes`() {
            val s = snap(axisX = 0f, axisY = 0f)
            assertThat(s.buttons[LogicalControl.DPAD_UP.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_DOWN.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_LEFT.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_RIGHT.index]).isZero()
        }

        // ── Narrow-band fix: slight drift on orthogonal axis stays cardinal ────

        @Test
        @DisplayName("right with tiny Y drift under deadzone: only RIGHT fires")
        fun `right with small y drift`() {
            val s = snap(axisX = 1.0f, axisY = 0.1f) // Y is within deadzone → zeroed
            assertThat(s.buttons[LogicalControl.DPAD_RIGHT.index]).isEqualTo(1.0f)
            assertThat(s.buttons[LogicalControl.DPAD_UP.index]).isZero()
            assertThat(s.buttons[LogicalControl.DPAD_DOWN.index]).isZero()
        }

        @Test
        @DisplayName("right with small Y drift beyond deadzone but angle still in RIGHT zone")
        fun `right with moderate y drift stays right`() {
            // X=0.95, Y=-0.16 → after deadzone: X=0.95, Y=-0.16 → atan2(-0.16, 0.95) ≈ -9.5° = 350.5°
            // 350.5° is in RIGHT zone [337.5, 360) ∪ [0, 22.5)
            val s = snap(axisX = 0.95f, axisY = -0.16f)
            assertThat(s.buttons[LogicalControl.DPAD_RIGHT.index]).isEqualTo(1.0f)
            assertThat(s.buttons[LogicalControl.DPAD_UP.index]).isZero()
        }

        // ── Fallback: non-standard d-pad bindings use half-axis ────────────────

        @Test
        @DisplayName("missing d-pad binding falls back to half-axis")
        fun `missing dpad binding falls back`() {
            val partialMapping = ControllerMapping(
                axisDirections = mapOf(
                    AxisDirection(MotionEvent.AXIS_X,  1) to LogicalControl.DPAD_RIGHT,
                    AxisDirection(MotionEvent.AXIS_X, -1) to LogicalControl.DPAD_LEFT,
                    // DOWN and UP missing → fallback
                )
            )
            val s = snap(partialMapping, axisX = 0.7071f, axisY = -0.7071f)
            // With half-axis: X=0.7071 > deadzone → RIGHT fires; Y=-0.7071 not bound → nothing
            assertThat(s.buttons[LogicalControl.DPAD_RIGHT.index]).isEqualTo(1.0f)
        }

        @Test
        @DisplayName("non-standard d-pad target falls back to half-axis")
        fun `non standard dpad target falls back`() {
            val weirdMapping = ControllerMapping(
                axisDirections = mapOf(
                    AxisDirection(MotionEvent.AXIS_X,  1) to LogicalControl.DPAD_RIGHT,
                    AxisDirection(MotionEvent.AXIS_X, -1) to LogicalControl.DPAD_LEFT,
                    AxisDirection(MotionEvent.AXIS_Y,  1) to LogicalControl.DPAD_DOWN,
                    AxisDirection(MotionEvent.AXIS_Y, -1) to LogicalControl.BUTTON_A // non-standard!
                )
            )
            val s = snap(weirdMapping, axisX = 0.9f, axisY = -0.9f)
            // Half-axis: X=0.9 → RIGHT fires; Y=-0.9 < 0 → BUTTON_A fires
            assertThat(s.buttons[LogicalControl.DPAD_RIGHT.index]).isEqualTo(1.0f)
            assertThat(s.buttons[LogicalControl.BUTTON_A.index]).isEqualTo(1.0f)
        }

        // ── Non-d-pad axis bindings still work via half-axis ───────────────────

        @Test
        @DisplayName("non-d-pad axis direction still fires via half-axis alongside angle d-pad")
        fun `non dpad axis direction works`() {
            val mapping = ControllerMapping(
                axisDirections = mapOf(
                    AxisDirection(MotionEvent.AXIS_X,  1) to LogicalControl.DPAD_RIGHT,
                    AxisDirection(MotionEvent.AXIS_X, -1) to LogicalControl.DPAD_LEFT,
                    AxisDirection(MotionEvent.AXIS_Y,  1) to LogicalControl.DPAD_DOWN,
                    AxisDirection(MotionEvent.AXIS_Y, -1) to LogicalControl.DPAD_UP,
                    AxisDirection(MotionEvent.AXIS_RX, 1) to LogicalControl.BUTTON_RB // extra
                )
            )
            val s = GamepadSnapshot.fromPhysicalInput(
                emptySet(),
                mapOf(
                    MotionEvent.AXIS_X to 1.0f,
                    MotionEvent.AXIS_Y to 0f,
                    MotionEvent.AXIS_RX to 0.8f
                ),
                mapping
            )
            assertThat(s.buttons[LogicalControl.DPAD_RIGHT.index]).isEqualTo(1.0f)
            assertThat(s.buttons[LogicalControl.BUTTON_RB.index]).isEqualTo(1.0f)
        }
    }
}
