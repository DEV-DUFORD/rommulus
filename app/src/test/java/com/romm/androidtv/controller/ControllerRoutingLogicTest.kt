package com.romm.androidtv.controller

import com.romm.androidtv.controller.model.*
import com.romm.androidtv.controller.policy.EventConsumptionPolicy
import com.romm.androidtv.controller.policy.SlotAssignmentPolicy
import com.romm.androidtv.controller.policy.SourceFilterPolicy
import com.romm.androidtv.controller.policy.SourceMask
import com.romm.androidtv.controller.util.AxisNormalizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for controller routing logic.
 *
 * Tests exercise production policy objects (SlotAssignmentPolicy,
 * EventConsumptionPolicy, SourceFilterPolicy, AxisNormalizer) rather
 * than hand-rolled test helpers. Android framework-dependent behavior
 * (KeyEvent/MotionEvent simulation) requires instrumented tests.
 */
@DisplayName("Controller routing — assignment, reconnect, slot limits, policy")
class ControllerRoutingLogicTest {

    // =========================================================================
    // SlotAssignmentPolicy tests (replaces hand-rolled assignDeviceToFirstAvailable)
    // =========================================================================

    @Nested
    @DisplayName("SlotAssignmentPolicy — deterministic session order")
    inner class SlotAssignmentTests {
        @Test
        @DisplayName("first device connects to slot 1")
        fun `first device to slot 1`() {
            val slots = ControllerSlot.createAllSlots()
            val sig = DeviceSignature("vid:045e-pid:02e0-src:1689", 0x045e, 0x02e0, "Xbox One")
            val idx = SlotAssignmentPolicy.findSlotForDevice(slots, sig)
            assertThat(idx).isEqualTo(0)

            val assigned = SlotAssignmentPolicy.applyAssignment(slots, idx, sig)
            assertThat(assigned[0].connectionState).isEqualTo(SlotConnectionState.CONNECTED)
            assertThat(assigned[0].preferredSignature).isEqualTo(sig)
            assertThat(assigned[1].connectionState).isEqualTo(SlotConnectionState.UNASSIGNED)
        }

        @Test
        @DisplayName("second device connects to slot 2")
        fun `second device to slot 2`() {
            val slots = ControllerSlot.createAllSlots()
            val sig1 = DeviceSignature("vid:045e-pid:02e0-src:1689", 0x045e, 0x02e0, "Xbox One")
            val sig2 = DeviceSignature("vid:054c-pid:0ce6-src:1689", 0x054c, 0x0ce6, "DualShock 4")

            val idx1 = SlotAssignmentPolicy.findSlotForDevice(slots, sig1)
            val s1 = SlotAssignmentPolicy.applyAssignment(slots, idx1, sig1)
            val idx2 = SlotAssignmentPolicy.findSlotForDevice(s1, sig2)
            val s2 = SlotAssignmentPolicy.applyAssignment(s1, idx2, sig2)

            assertThat(s2[0].preferredSignature).isEqualTo(sig1)
            assertThat(s2[1].preferredSignature).isEqualTo(sig2)
            assertThat(s2[2].connectionState).isEqualTo(SlotConnectionState.UNASSIGNED)
        }

        @Test
        @DisplayName("fifth device is rejected when all slots full")
        fun `fifth device rejected`() {
            val slots = ControllerSlot.createAllSlots()
            var current = slots
            // Fill all 4 slots (W3C browser contract)
            for (i in 1..4) {
                val sig = DeviceSignature("vid:0000-pid:000${i}-src:1689", i, i, "D$i")
                val idx = SlotAssignmentPolicy.findSlotForDevice(current, sig)
                current = SlotAssignmentPolicy.applyAssignment(current, idx, sig)
            }

            // Fifth device — all slots full
            val sig5 = DeviceSignature("vid:0000-pid:0005-src:1689", 5, 5, "D5")
            val idx5 = SlotAssignmentPolicy.findSlotForDevice(current, sig5)
            assertThat(idx5).isEqualTo(-1)

            assertThat(current[0].connectionState).isEqualTo(SlotConnectionState.CONNECTED)
            assertThat(current[1].connectionState).isEqualTo(SlotConnectionState.CONNECTED)
            assertThat(current[2].connectionState).isEqualTo(SlotConnectionState.CONNECTED)
            assertThat(current[3].connectionState).isEqualTo(SlotConnectionState.CONNECTED)
        }

        @Test
        @DisplayName("disconnected device reconnects to same slot via reconnect key")
        fun `reconnect to same slot`() {
            val slots = ControllerSlot.createAllSlots()
            val sig = DeviceSignature("vid:045e-pid:02e0-src:1689", 0x045e, 0x02e0, "Xbox One")

            // Assign to slot 1
            val idx = SlotAssignmentPolicy.findSlotForDevice(slots, sig)
            val assigned = SlotAssignmentPolicy.applyAssignment(slots, idx, sig)
            assertThat(assigned[0].preferredSignature).isEqualTo(sig)

            // Disconnect
            val disconnected = SlotAssignmentPolicy.applyDisconnect(assigned, idx)
            assertThat(disconnected[0].connectionState).isEqualTo(SlotConnectionState.DISCONNECTED)

            // Reconnect: should go back to slot 1 (same reconnect key)
            val reconnectIdx = SlotAssignmentPolicy.findSlotForDevice(disconnected, sig)
            assertThat(reconnectIdx).isEqualTo(0)
            val reconnected = SlotAssignmentPolicy.applyAssignment(disconnected, reconnectIdx, sig)
            assertThat(reconnected[0].connectionState).isEqualTo(SlotConnectionState.CONNECTED)
            assertThat(reconnected[1].connectionState).isEqualTo(SlotConnectionState.UNASSIGNED)
        }

        @Test
        @DisplayName("reconnect works despite display name change")
        fun `reconnect survives name change`() {
            val slots = ControllerSlot.createAllSlots()
            val sigOriginal = DeviceSignature("vid:045e-pid:02e0-src:1689", 0x045e, 0x02e0, "Xbox One")
            val sigRenamed = DeviceSignature("vid:045e-pid:02e0-src:1689", 0x045e, 0x02e0, "Xbox Controller - Updated Firmware")

            // Assign
            val idx = SlotAssignmentPolicy.findSlotForDevice(slots, sigOriginal)
            val assigned = SlotAssignmentPolicy.applyAssignment(slots, idx, sigOriginal)

            // Disconnect
            val disconnected = SlotAssignmentPolicy.applyDisconnect(assigned, idx)

            // Reconnect with new name — should still match slot 1 via reconnect key
            val reconnectIdx = SlotAssignmentPolicy.findSlotForDevice(disconnected, sigRenamed)
            assertThat(reconnectIdx).isEqualTo(0)
            assertThat(sigOriginal.matchesReconnect(sigRenamed)).isTrue()
        }

        @Test
        @DisplayName("two identical controllers are disambiguated by session order")
        fun `duplicate devices assigned to different slots`() {
            val slots = ControllerSlot.createAllSlots()
            val sig = DeviceSignature("vid:045e-pid:02e0-src:1689", 0x045e, 0x02e0, "Xbox Controller")

            // First identical controller -> slot 1
            val idx1 = SlotAssignmentPolicy.findSlotForDevice(slots, sig)
            val s1 = SlotAssignmentPolicy.applyAssignment(slots, idx1, sig)
            assertThat(idx1).isEqualTo(0)

            // Second identical controller: slot 1 is CONNECTED, so goes to slot 2
            val idx2 = SlotAssignmentPolicy.findSlotForDevice(s1, sig)
            val s2 = SlotAssignmentPolicy.applyAssignment(s1, idx2, sig)
            assertThat(idx2).isEqualTo(1)

            assertThat(s2[0].preferredSignature).isEqualTo(sig)
            assertThat(s2[1].preferredSignature).isEqualTo(sig)
        }

        @Test
        @DisplayName("disconnect preserves mapping for later reconnect")
        fun `disconnect preserves mapping`() {
            val slots = ControllerSlot.createAllSlots()
            val sig = DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "Test")

            // Assign with custom mapping
            val customMapping = ControllerMapping.swapAB(ControllerMapping())
            val idx = SlotAssignmentPolicy.findSlotForDevice(slots, sig)
            var assigned = SlotAssignmentPolicy.applyAssignment(slots, idx, sig)
            assigned = assigned.toMutableList().apply {
                this[0] = this[0].remap(customMapping)
            } as List<ControllerSlot>

            // Disconnect
            val disconnected = SlotAssignmentPolicy.applyDisconnect(assigned, idx)
            assertThat(disconnected[0].connectionState).isEqualTo(SlotConnectionState.DISCONNECTED)
            assertThat(disconnected[0].mapping).isEqualTo(customMapping)

            // Reconnect — mapping preserved
            val reconnectIdx = SlotAssignmentPolicy.findSlotForDevice(disconnected, sig)
            val reconnected = SlotAssignmentPolicy.applyAssignment(disconnected, reconnectIdx, sig)
            assertThat(reconnected[0].mapping).isEqualTo(customMapping)
        }

        @Test
        @DisplayName("clearAllSlots emits neutral snapshots for all assigned slots")
        fun `clear all slots on stop`() {
            val slots = ControllerSlot.createAllSlots()
            val sig1 = DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "D1")
            val sig3 = DeviceSignature("vid:0003-pid:0003-src:1689", 3, 3, "D3")

            // Assign to first two available slots (indices 0 and 1)
            var current = slots
            val idx1 = SlotAssignmentPolicy.findSlotForDevice(current, sig1)
            current = SlotAssignmentPolicy.applyAssignment(current, idx1, sig1)
            val idx3 = SlotAssignmentPolicy.findSlotForDevice(current, sig3)
            current = SlotAssignmentPolicy.applyAssignment(current, idx3, sig3)

            // Set active snapshots on assigned slots
            current = current.toMutableList().apply {
                this[0] = this[0].updateSnapshot(
                    GamepadSnapshot.withButton(GamepadSnapshot.EMPTY, LogicalControl.BUTTON_A, true)
                )
                this[1] = this[1].updateSnapshot(
                    GamepadSnapshot.withButton(GamepadSnapshot.EMPTY, LogicalControl.BUTTON_B, true)
                )
            }

            // Clear all slots (simulates router stop)
            val cleared = SlotAssignmentPolicy.clearAllSlots(current)

            assertThat(cleared[0].connectionState).isEqualTo(SlotConnectionState.DISCONNECTED)
            assertThat(cleared[0].currentSnapshot).isEqualTo(GamepadSnapshot.EMPTY)
            assertThat(cleared[1].connectionState).isEqualTo(SlotConnectionState.DISCONNECTED)
            assertThat(cleared[1].currentSnapshot).isEqualTo(GamepadSnapshot.EMPTY)
            // Unassigned slots remain unassigned
            assertThat(cleared[2].connectionState).isEqualTo(SlotConnectionState.UNASSIGNED)
            assertThat(cleared[3].connectionState).isEqualTo(SlotConnectionState.UNASSIGNED)
        }

        @Test
        @DisplayName("clearAllSlots preserves UNASSIGNED slots")
        fun `clear all slots preserves unassigned`() {
            val slots = ControllerSlot.createAllSlots()
            val cleared = SlotAssignmentPolicy.clearAllSlots(slots)
            cleared.forEach { s ->
                assertThat(s.connectionState).isEqualTo(SlotConnectionState.UNASSIGNED)
            }
        }
    }

    // =========================================================================
    // EventConsumptionPolicy tests
    // =========================================================================

    @Nested
    @DisplayName("EventConsumptionPolicy — key event filtering")
    inner class EventConsumptionPolicyTests {
        @Test
        @DisplayName("controller button key is consumed")
        fun `controller key event consumed`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(
                android.view.KeyEvent.KEYCODE_BUTTON_A
            )).isTrue()
        }

        @Test
        @DisplayName("D-pad key from controller is consumed")
        fun `dpad key consumed`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(
                android.view.KeyEvent.KEYCODE_DPAD_UP
            )).isTrue()
        }

        @Test
        @DisplayName("Android Back is NOT consumed")
        fun `back key never consumed`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(
                android.view.KeyEvent.KEYCODE_BACK
            )).isFalse()
            assertThat(EventConsumptionPolicy.isBackKey(
                android.view.KeyEvent.KEYCODE_BACK
            )).isTrue()
        }

        @Test
        @DisplayName("unmapped key is NOT consumed")
        fun `unmapped key not consumed`() {
            // KEYCODE_A (typewriter 'a') is not in KEYCODE_TO_CONTROL
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(
                android.view.KeyEvent.KEYCODE_A
            )).isFalse()
        }

        @Test
        @DisplayName("WebView receives only translated snapshots, not raw events")
        fun `only translated output`() {
            val mapping = ControllerMapping.swapAB(ControllerMapping())
            val pressed = setOf(NeutralKey.BUTTON_A)
            val snap = GamepadSnapshot.fromPhysicalInput(pressed, emptyMap(), mapping)

            assertThat(snap.buttons[LogicalControl.BUTTON_B.index]).isEqualTo(1.0f)
            assertThat(snap.buttons[LogicalControl.BUTTON_A.index]).isZero()
        }
    }

    // =========================================================================
    // SourceFilterPolicy tests
    // =========================================================================

    @Nested
    @DisplayName("SourceFilterPolicy — device classification")
    inner class SourceFilterTests {
        private val SOURCE_GAMEPAD = SourceMask.GAMEPAD
        private val SOURCE_JOYSTICK = SourceMask.JOYSTICK
        private val SOURCE_DPAD = SourceMask.DPAD

        @Test
        @DisplayName("GAMEPAD source is a controller")
        fun `gamepad is controller`() {
            assertThat(SourceFilterPolicy.isControllerSource(SOURCE_GAMEPAD)).isTrue()
        }

        @Test
        @DisplayName("JOYSTICK source is a controller")
        fun `joystick is controller`() {
            assertThat(SourceFilterPolicy.isControllerSource(SOURCE_JOYSTICK)).isTrue()
        }

        @Test
        @DisplayName("DPAD source is a potential controller")
        fun `dpad is potential controller`() {
            assertThat(SourceFilterPolicy.isControllerSource(SOURCE_DPAD)).isTrue()
        }

        @Test
        @DisplayName("unrelated source bit is NOT a controller")
        fun `unrelated source not controller`() {
            assertThat(SourceFilterPolicy.isControllerSource(0x00001000)).isFalse()
        }

        @Test
        @DisplayName("GAMEPAD device is NOT a TV remote")
        fun `gamepad not remote`() {
            assertThat(SourceFilterPolicy.isTvRemote(SOURCE_GAMEPAD, 0)).isFalse()
        }

        @Test
        @DisplayName("JOYSTICK device is NOT a TV remote")
        fun `joystick not remote`() {
            assertThat(SourceFilterPolicy.isTvRemote(SOURCE_JOYSTICK, 0)).isFalse()
        }

        @Test
        @DisplayName("DPAD-only with joystick axes is NOT a remote")
        fun `dpad with axes not remote`() {
            // A retro gamepad: DPAD source + joystick axes
            assertThat(SourceFilterPolicy.isTvRemote(SOURCE_DPAD, 2)).isFalse()
        }

        @Test
        @DisplayName("DPAD-only without joystick axes IS a remote")
        fun `dpad without axes is remote`() {
            // A TV remote: DPAD source, no joystick axes
            assertThat(SourceFilterPolicy.isTvRemote(SOURCE_DPAD, 0)).isTrue()
        }

        @Test
        @DisplayName("GAMEPAD+DPAD combined source is NOT a remote")
        fun `gamepad dpad combo not remote`() {
            assertThat(SourceFilterPolicy.isTvRemote(
                SOURCE_GAMEPAD or SOURCE_DPAD, 0
            )).isFalse()
        }
    }

    // =========================================================================
    // AxisNormalizer tests (corrected flat-region logic)
    // =========================================================================

    @Nested
    @DisplayName("AxisNormalizer — hardware deadzone and normalization")
    inner class AxisNormalizerTests {
        @Test
        @DisplayName("flat region zeroes values within [-flat, +flat]")
        fun `flat region zeroes small values`() {
            // Device reports flat=0.1, so values in [-0.1, 0.1] are deadzone
            assertThat(AxisNormalizer.normalize(0.05f, -1f, 1f, 0.1f)).isZero()
            assertThat(AxisNormalizer.normalize(-0.05f, -1f, 1f, 0.1f)).isZero()
            assertThat(AxisNormalizer.normalize(0.0f, -1f, 1f, 0.1f)).isZero()
        }

        @Test
        @DisplayName("flat region boundary values pass through")
        fun `flat region boundary`() {
            // flat=0.1: exactly 0.1 is NOT in deadzone (rawValue <= rangeFlat is inclusive)
            assertThat(AxisNormalizer.normalize(0.1f, -1f, 1f, 0.1f)).isZero()
            assertThat(AxisNormalizer.normalize(-0.1f, -1f, 1f, 0.1f)).isZero()
            // Just outside
            assertThat(AxisNormalizer.normalize(0.1001f, -1f, 1f, 0.1f)).isGreaterThan(0f)
            assertThat(AxisNormalizer.normalize(-0.1001f, -1f, 1f, 0.1f)).isLessThan(0f)
        }

        @Test
        @DisplayName("normalizes full range to [-1, +1]")
        fun `full range normalization`() {
            assertThat(AxisNormalizer.normalize(-1f, -1f, 1f, 0f)).isEqualTo(-1f)
            assertThat(AxisNormalizer.normalize(0f, -1f, 1f, 0f)).isZero()
            assertThat(AxisNormalizer.normalize(1f, -1f, 1f, 0f)).isEqualTo(1f)
        }

        @Test
        @DisplayName("normalizes non-standard range")
        fun `non standard range`() {
            // Device reports range [0, 255], flat=10
            assertThat(AxisNormalizer.normalize(127f, 0f, 255f, 10f)).isZero()
            assertThat(AxisNormalizer.normalize(127f, 0f, 255f, 0f)).isCloseTo(-0.0f, org.assertj.core.data.Offset.offset(0.01f))
            assertThat(AxisNormalizer.normalize(255f, 0f, 255f, 0f)).isEqualTo(1f)
        }

        @Test
        @DisplayName("fallback clamps to [-1, +1]")
        fun `fallback normalization`() {
            assertThat(AxisNormalizer.normalizeFallback(0.5f)).isEqualTo(0.5f)
            assertThat(AxisNormalizer.normalizeFallback(2.0f)).isEqualTo(1f)
            assertThat(AxisNormalizer.normalizeFallback(-3.0f)).isEqualTo(-1f)
        }

        @Test
        @DisplayName("flat=0 passes all values through")
        fun `zero flat`() {
            assertThat(AxisNormalizer.normalize(0.001f, -1f, 1f, 0f))
                .isCloseTo(0.001f, org.assertj.core.data.Offset.offset(0.0001f))
            assertThat(AxisNormalizer.normalize(-0.001f, -1f, 1f, 0f))
                .isCloseTo(-0.001f, org.assertj.core.data.Offset.offset(0.0001f))
        }
    }

    // =========================================================================
    // Immutable replacement tests (unchanged behavior, confirmed)
    // =========================================================================

    @Nested
    @DisplayName("Immutable immediate replacement")
    inner class ImmutableReplacementTests {
        @Test
        @DisplayName("mapping change replaces snapshot immediately")
        fun `immediate mapping replacement`() {
            val slot = ControllerSlot(playerNumber = 1)
                .assign(DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "Test"))

            val snapA = GamepadSnapshot.withButton(
                GamepadSnapshot.EMPTY, LogicalControl.BUTTON_A, true
            )
            val slotWithSnap = slot.updateSnapshot(snapA)
            assertThat(slotWithSnap.currentSnapshot.buttons[LogicalControl.BUTTON_A.index])
                .isEqualTo(1.0f)

            val newMapping = ControllerMapping.swapAB(slotWithSnap.mapping)
            val remappedSlot = slotWithSnap.remap(newMapping)

            assertThat(remappedSlot.currentSnapshot.buttons[LogicalControl.BUTTON_A.index])
                .isEqualTo(1.0f)

            val rebuilt = GamepadSnapshot.fromPhysicalInput(
                setOf(NeutralKey.BUTTON_A),
                emptyMap(),
                remappedSlot.mapping
            )
            assertThat(rebuilt.buttons[LogicalControl.BUTTON_B.index]).isEqualTo(1.0f)
        }

        @Test
        @DisplayName("slot state is immutable — original unchanged after update")
        fun `slot immutability`() {
            val original = ControllerSlot(playerNumber = 1)
            val updated = original.assign(
                DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "Test")
            )

            assertThat(original.preferredSignature).isNull()
            assertThat(updated.preferredSignature).isNotNull()
        }

        @Test
        @DisplayName("snapshot is immutable — copyOnWrite semantics")
        fun `snapshot immutability`() {
            val base = GamepadSnapshot.EMPTY
            val withA = GamepadSnapshot.withButton(base, LogicalControl.BUTTON_A, true)
            val withB = GamepadSnapshot.withButton(withA, LogicalControl.BUTTON_B, true)

            assertThat(base.buttons[0]).isZero()
            assertThat(base.buttons[1]).isZero()
            assertThat(withA.buttons[0]).isEqualTo(1.0f)
            assertThat(withA.buttons[1]).isZero()
            assertThat(withB.buttons[0]).isEqualTo(1.0f)
            assertThat(withB.buttons[1]).isEqualTo(1.0f)
        }
    }

    // =========================================================================
    // DeviceSignature reconnect identity tests
    // =========================================================================

    @Nested
    @DisplayName("DeviceSignature — reconnect identity")
    inner class DeviceSignatureTests {
        @Test
        @DisplayName("identical VID/PID produce matching reconnect keys")
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

        @Test
        @DisplayName("descriptor includes source mask for uniqueness")
        fun `descriptor includes sources`() {
            val sig1 = DeviceSignature("vid:045e-pid:02e0-src:1689", 0x045e, 0x02e0, "Gamepad")
            val sig2 = DeviceSignature("vid:045e-pid:02e0-src:257", 0x045e, 0x02e0, "Keyboard")
            assertThat(sig1).isNotEqualTo(sig2)
            assertThat(sig1.matchesReconnect(sig2)).isFalse()
        }
    }
}
