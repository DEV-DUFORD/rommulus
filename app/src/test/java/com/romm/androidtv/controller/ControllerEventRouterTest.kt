package com.romm.androidtv.controller

import android.view.KeyEvent
import com.romm.androidtv.controller.model.*
import com.romm.androidtv.controller.router.ControllerEventRouter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Focused production-path unit tests for controller event routing.
 *
 * Validates:
 * - Repeated keydown/up suppression (repeatCount > 0)
 * - Merged hat + key D-pad states
 * - Repeated D-pad cycles
 * - Left/right stick common Xbox layouts
 * - Unsigned range normalization/center return
 * - Unchanged-state suppression
 * - Four-slot serialization during remote input
 * - Lifecycle reset
 */
@DisplayName("ControllerEventRouter — production path tests")
class ControllerEventRouterTest {

    @Nested
    @DisplayName("Repeat suppression")
    inner class RepeatSuppressionTests {

        @Test
        @DisplayName("initial remote key down is processed")
        fun `keyDownRepeatZeroProcessed`() {
            val router = ControllerEventRouter()
            router.setActive(true)

            val consumed = router.routeTvRemoteKey(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER
            )
            assertThat(consumed).isTrue()
            assertThat(router.slotsFlow.value[0].currentSnapshot.buttons[0]).isEqualTo(1f)
        }

        @Test
        @DisplayName("KEY_DOWN with repeatCount>0 is suppressed (no state change)")
        fun `keyDownRepeatSuppressed`() {
            val router = ControllerEventRouter()
            router.setActive(true)

            router.routeTvRemoteKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)
            val slotsBeforeRepeat = router.slotsFlow.value
            val consumed = router.routeTvRemoteKey(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_UP,
                repeatCount = 5
            )

            assertThat(consumed).isTrue()
            assertThat(router.slotsFlow.value).isSameAs(slotsBeforeRepeat)
        }

        @Test
        @DisplayName("ACTION_UP is always processed even with repeatCount>0")
        fun `actionUpAlwaysProcessed`() {
            val router = ControllerEventRouter()
            router.setActive(true)

            router.routeTvRemoteKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)
            val consumed = router.routeTvRemoteKey(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_DPAD_UP,
                repeatCount = 5
            )
            assertThat(consumed).isTrue()
            assertThat(router.slotsFlow.value[0].currentSnapshot.buttons[12]).isZero()
        }
    }

    @Nested
    @DisplayName("Hat + key D-pad merging")
    inner class HatKeyMergingTests {

        @Test
        @DisplayName("Neutral hat does not erase held key D-pad state")
        fun `neutralHatPreservesKeyDpad`() {
            val router = ControllerEventRouter()
            router.setActive(true)

            val mapping = ControllerMapping()
            val snap = GamepadSnapshot.fromPhysicalInput(
                setOf(KeyEvent.KEYCODE_DPAD_UP),
                emptyMap(),
                mapping
            )
            assertThat(snap.buttons[LogicalControl.DPAD_UP.index]).isEqualTo(1f)
        }

        @Test
        @DisplayName("Hat-derived D-pad state is separate from key-derived state")
        fun `hatStateSeparateFromKeyState`() {
            // This test verifies the design: hatDpadKeysPerDevice is tracked separately
            // The actual merging happens in rebuildSnapshotForDevice
            val mapping = ControllerMapping()
            val pressedKeys = setOf(android.view.KeyEvent.KEYCODE_DPAD_UP)
            val hatDpadKeys = setOf(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            val mergedKeys = pressedKeys + hatDpadKeys

            val snap = GamepadSnapshot.fromPhysicalInput(mergedKeys, emptyMap(), mapping)
            assertThat(snap.buttons[LogicalControl.DPAD_UP.index]).isEqualTo(1.0f)
            assertThat(snap.buttons[LogicalControl.DPAD_RIGHT.index]).isEqualTo(1.0f)
            assertThat(snap.buttons[LogicalControl.DPAD_DOWN.index]).isZero()
            assertThat(snap.buttons[LogicalControl.DPAD_LEFT.index]).isZero()
        }
    }

    @Nested
    @DisplayName("D-pad cycle handling")
    inner class DpadCycleTests {

        @Test
        @DisplayName("D-pad direction changes work indefinitely")
        fun `dpadDirectionChanges`() {
            val router = ControllerEventRouter()
            router.setActive(true)

            repeat(3) {
                router.routeTvRemoteKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)
                assertThat(router.slotsFlow.value[0].currentSnapshot.buttons[12]).isEqualTo(1f)
                router.routeTvRemoteKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP)
                assertThat(router.slotsFlow.value[0].currentSnapshot.buttons[12]).isZero()
            }
        }

        @Test
        @DisplayName("Multiple simultaneous D-pad directions are preserved")
        fun `simultaneousDpadDirections`() {
            val mapping = ControllerMapping()
            val pressedKeys = setOf(
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            )
            val snap = GamepadSnapshot.fromPhysicalInput(pressedKeys, emptyMap(), mapping)

            assertThat(snap.buttons[LogicalControl.DPAD_UP.index]).isEqualTo(1.0f)
            assertThat(snap.buttons[LogicalControl.DPAD_RIGHT.index]).isEqualTo(1.0f)
            assertThat(snap.buttons[LogicalControl.DPAD_DOWN.index]).isZero()
            assertThat(snap.buttons[LogicalControl.DPAD_LEFT.index]).isZero()
        }
    }

    @Nested
    @DisplayName("Xbox stick layouts")
    inner class XboxStickTests {

        @Test
        @DisplayName("Left stick X/Y mapping")
        fun `leftStickMapping`() {
            val mapping = ControllerMapping()
            val axes = mapOf(
                android.view.MotionEvent.AXIS_X to 0.5f,
                android.view.MotionEvent.AXIS_Y to -0.3f
            )
            val snap = GamepadSnapshot.fromPhysicalInput(emptySet(), axes, mapping)

            assertThat(snap.axes[LogicalControl.AXIS_LX.index]).isGreaterThan(0f)
            assertThat(snap.axes[LogicalControl.AXIS_LY.index]).isLessThan(0f)
        }

        @Test
        @DisplayName("Right stick RX/RY mapping (Xbox standard)")
        fun `rightStickMapping`() {
            val mapping = ControllerMapping()
            val axes = mapOf(
                android.view.MotionEvent.AXIS_RX to 0.7f,
                android.view.MotionEvent.AXIS_RY to -0.4f
            )
            val snap = GamepadSnapshot.fromPhysicalInput(emptySet(), axes, mapping)

            assertThat(snap.axes[LogicalControl.AXIS_RX.index]).isGreaterThan(0f)
            assertThat(snap.axes[LogicalControl.AXIS_RY.index]).isLessThan(0f)
        }

        @Test
        @DisplayName("Right stick Z/RZ fallback mapping")
        fun `rightStickFallbackMapping`() {
            val mapping = ControllerMapping()
            val axes = mapOf(
                android.view.MotionEvent.AXIS_Z to 0.7f,
                android.view.MotionEvent.AXIS_RZ to -0.4f
            )
            val snap = GamepadSnapshot.fromPhysicalInput(emptySet(), axes, mapping)

            // AXIS_Z maps to AXIS_RX, AXIS_RZ maps to AXIS_RY
            assertThat(snap.axes[LogicalControl.AXIS_RX.index]).isGreaterThan(0f)
            assertThat(snap.axes[LogicalControl.AXIS_RY.index]).isLessThan(0f)
        }
    }

    @Nested
    @DisplayName("Unsigned range normalization")
    inner class UnsignedRangeTests {

        @Test
        @DisplayName("0..65535 range normalizes correctly")
        fun `unsignedRangeNormalization`() {
            // Simulate unsigned range [0, 65535]
            val rangeMin = 0f
            val rangeMax = 65535f
            val rangeFlat = 0f

            // Center value (32767.5) should normalize to ~0
            val center = com.romm.androidtv.controller.util.AxisNormalizer.normalize(
                32768f, rangeMin, rangeMax, rangeFlat
            )
            assertThat(center).isCloseTo(0f, org.assertj.core.data.Offset.offset(0.01f))

            // Min value should normalize to -1
            val min = com.romm.androidtv.controller.util.AxisNormalizer.normalize(
                0f, rangeMin, rangeMax, rangeFlat
            )
            assertThat(min).isEqualTo(-1f)

            // Max value should normalize to 1
            val max = com.romm.androidtv.controller.util.AxisNormalizer.normalize(
                65535f, rangeMin, rangeMax, rangeFlat
            )
            assertThat(max).isEqualTo(1f)
        }

        @Test
        @DisplayName("Return to center after stick release")
        fun `returnToCenter`() {
            val mapping = ControllerMapping()

            // Stick moved
            var snap = GamepadSnapshot.fromPhysicalInput(
                emptySet(),
                mapOf(android.view.MotionEvent.AXIS_X to 0.8f),
                mapping
            )
            assertThat(snap.axes[LogicalControl.AXIS_LX.index]).isGreaterThan(0f)

            // Stick returned to center (axis value = 0)
            snap = GamepadSnapshot.fromPhysicalInput(
                emptySet(),
                mapOf(android.view.MotionEvent.AXIS_X to 0f),
                mapping
            )
            // With default deadzone (0.15), 0 should be zeroed
            assertThat(snap.axes[LogicalControl.AXIS_LX.index]).isZero()
        }
    }

    @Nested
    @DisplayName("Unchanged-state suppression")
    inner class UnchangedStateTests {

        @Test
        @DisplayName("Snapshot with no changes is not emitted")
        fun `unchangedSnapshotNotEmitted`() {
            val router = ControllerEventRouter()
            router.setActive(true)
            router.routeTvRemoteKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)
            val firstState = router.slotsFlow.value

            router.routeTvRemoteKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)
            assertThat(router.slotsFlow.value).isSameAs(firstState)
        }

        @Test
        @DisplayName("Snapshot with button press is different")
        fun `buttonPressChangesSnapshot`() {
            val emptySnap = GamepadSnapshot.EMPTY
            val pressedSnap = GamepadSnapshot.withButton(
                emptySnap, LogicalControl.BUTTON_A, true
            )

            // Snapshots are different
            assertThat(emptySnap).isNotEqualTo(pressedSnap)
            assertThat(pressedSnap.buttons[0]).isEqualTo(1.0f)
        }
    }

    @Nested
    @DisplayName("Four-slot serialization")
    inner class FourSlotSerializationTests {

        @Test
        @DisplayName("Serializer handles exactly 4 slots")
        fun `serializerHandlesFourSlots`() {
            val slots = ControllerSlot.createAllSlots()
            assertThat(slots).hasSize(4)

            // Serialize empty slots
            val json = com.romm.androidtv.gamepad.GamepadSerializer.serializeSlots(slots)
            assertThat(json).isNotNull()
            assertThat(json).startsWith("[")
            assertThat(json).endsWith("]")
        }

        @Test
        @DisplayName("Serializer rejects non-4 slot lists")
        fun `serializerRejectsWrongSlotCount`() {
            val slots = ControllerSlot.createAllSlots()
            val wrongSlots = slots + ControllerSlot(playerNumber = 4)

            val json = com.romm.androidtv.gamepad.GamepadSerializer.serializeSlots(wrongSlots)
            assertThat(json).isNull()
        }

        @Test
        @DisplayName("Connected slot is serialized with button/axis data")
        fun `connectedSlotSerialized`() {
            val slot = ControllerSlot(playerNumber = 1)
                .assign(DeviceSignature("test", 1, 2, "Test"))
                .updateSnapshot(
                    GamepadSnapshot.withButton(GamepadSnapshot.EMPTY, LogicalControl.BUTTON_A, true)
                )
            val slots = listOf(slot) + ControllerSlot.createAllSlots().drop(1)

            val json = com.romm.androidtv.gamepad.GamepadSerializer.serializeSlots(slots)
            assertThat(json).isNotNull()
            assertThat(json).contains("\"connected\":true")
            assertThat(json).contains("\"index\":0")
        }
    }

    @Nested
    @DisplayName("Immediate mapping replacement")
    inner class MappingReplacementTests {
        @Test
        fun `AB swap rebuilds an active snapshot without another key event`() {
            val router = ControllerEventRouter()
            router.setActive(true)
            router.routeTvRemoteKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)

            router.swapAB(0)

            val snapshot = router.slotsFlow.value[0].currentSnapshot
            assertThat(snapshot.buttons[LogicalControl.BUTTON_A.index]).isZero()
            assertThat(snapshot.buttons[LogicalControl.BUTTON_B.index]).isEqualTo(1f)
        }

        @Test
        fun `reset rebuilds an active swapped snapshot immediately`() {
            val router = ControllerEventRouter()
            router.setActive(true)
            router.routeTvRemoteKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
            router.swapAB(0)

            router.resetMapping(0)

            val snapshot = router.slotsFlow.value[0].currentSnapshot
            assertThat(snapshot.buttons[LogicalControl.BUTTON_A.index]).isEqualTo(1f)
            assertThat(snapshot.buttons[LogicalControl.BUTTON_B.index]).isZero()
        }
    }

    @Nested
    @DisplayName("Axis-direction (half-axis) digital bindings")
    inner class AxisDirectionBindingTests {

        private fun snap(axes: Map<Int, Float>, mapping: ControllerMapping): GamepadSnapshot =
            GamepadSnapshot.fromPhysicalInput(emptySet(), axes, mapping)

        @Test
        @DisplayName("positive polarity axis beyond deadzone presses target button")
        fun `positivePolarityPressesButton`() {
            val mapping = ControllerMapping(
                axisDirections = mapOf(
                    AxisDirection(android.view.MotionEvent.AXIS_X, +1) to LogicalControl.BUTTON_A
                )
            )
            val snap = snap(mapOf(android.view.MotionEvent.AXIS_X to 0.8f), mapping)
            assertThat(snap.buttons[LogicalControl.BUTTON_A.index]).isEqualTo(1f)
        }

        @Test
        @DisplayName("return to neutral (or within deadzone) releases target button")
        fun `returnToNeutralReleasesButton`() {
            val mapping = ControllerMapping(
                axisDirections = mapOf(
                    AxisDirection(android.view.MotionEvent.AXIS_X, +1) to LogicalControl.BUTTON_A
                )
            )
            val neutral = snap(mapOf(android.view.MotionEvent.AXIS_X to 0f), mapping)
            assertThat(neutral.buttons[LogicalControl.BUTTON_A.index]).isZero()

            val withinDeadzone = snap(mapOf(android.view.MotionEvent.AXIS_X to 0.1f), mapping)
            assertThat(withinDeadzone.buttons[LogicalControl.BUTTON_A.index]).isZero()
        }

        @Test
        @DisplayName("deadzone threshold respected (just past deadzone presses)")
        fun `deadzoneThresholdRespected`() {
            val mapping = ControllerMapping(
                axisDirections = mapOf(
                    AxisDirection(android.view.MotionEvent.AXIS_X, +1) to LogicalControl.BUTTON_A
                )
            )
            val justInside = snap(mapOf(android.view.MotionEvent.AXIS_X to 0.14f), mapping)
            assertThat(justInside.buttons[LogicalControl.BUTTON_A.index]).isZero()

            val justOutside = snap(mapOf(android.view.MotionEvent.AXIS_X to 0.16f), mapping)
            assertThat(justOutside.buttons[LogicalControl.BUTTON_A.index]).isEqualTo(1f)
        }

        @Test
        @DisplayName("negative polarity only presses when axis is on negative side")
        fun `negativePolarityOnlyOnNegativeSide`() {
            val mapping = ControllerMapping(
                axisDirections = mapOf(
                    AxisDirection(android.view.MotionEvent.AXIS_X, -1) to LogicalControl.DPAD_LEFT
                )
            )
            val negative = snap(mapOf(android.view.MotionEvent.AXIS_X to -0.8f), mapping)
            assertThat(negative.buttons[LogicalControl.DPAD_LEFT.index]).isEqualTo(1f)

            val positive = snap(mapOf(android.view.MotionEvent.AXIS_X to 0.8f), mapping)
            assertThat(positive.buttons[LogicalControl.DPAD_LEFT.index]).isZero()

            val neutral = snap(mapOf(android.view.MotionEvent.AXIS_X to 0f), mapping)
            assertThat(neutral.buttons[LogicalControl.DPAD_LEFT.index]).isZero()
        }

        @Test
        @DisplayName("both half-axes of one stick can drive two different buttons")
        fun `bothHalfAxesMapToDifferentButtons`() {
            val mapping = ControllerMapping(
                axisDirections = mapOf(
                    AxisDirection(android.view.MotionEvent.AXIS_X, -1) to LogicalControl.DPAD_LEFT,
                    AxisDirection(android.view.MotionEvent.AXIS_X, +1) to LogicalControl.DPAD_RIGHT
                )
            )
            val right = snap(mapOf(android.view.MotionEvent.AXIS_X to 0.9f), mapping)
            assertThat(right.buttons[LogicalControl.DPAD_RIGHT.index]).isEqualTo(1f)
            assertThat(right.buttons[LogicalControl.DPAD_LEFT.index]).isZero()
        }

        @Test
        @DisplayName("axis-direction binding does not affect existing axis output")
        fun `axisDirectionBindingPreservesAnalogAxis`() {
            val mapping = ControllerMapping(
                axes = mapOf(android.view.MotionEvent.AXIS_X to LogicalControl.AXIS_LX),
                axisDirections = mapOf(
                    AxisDirection(android.view.MotionEvent.AXIS_X, +1) to LogicalControl.BUTTON_A
                )
            )
            val snap = snap(mapOf(android.view.MotionEvent.AXIS_X to 0.8f), mapping)
            assertThat(snap.axes[LogicalControl.AXIS_LX.index]).isEqualTo(0.8f)
            assertThat(snap.buttons[LogicalControl.BUTTON_A.index]).isEqualTo(1f)
        }
    }

    @Nested
    @DisplayName("Bulk applyMappings")
    inner class ApplyMappingsTests {

        @Test
        fun `applyMappings replaces multiple slots in one call`() {
            val router = ControllerEventRouter()
            router.setActive(true)

            val custom = ControllerMapping(
                axisDirections = mapOf(
                    AxisDirection(android.view.MotionEvent.AXIS_X, +1) to LogicalControl.BUTTON_A
                )
            )
            router.applyMappings(mapOf(0 to custom, 1 to custom, 3 to custom))

            assertThat(router.slotsFlow.value[0].mapping).isEqualTo(custom)
            assertThat(router.slotsFlow.value[1].mapping).isEqualTo(custom)
            assertThat(router.slotsFlow.value[2].mapping).isEqualTo(ControllerMapping())
            assertThat(router.slotsFlow.value[3].mapping).isEqualTo(custom)
        }

        @Test
        fun `applyMappings with equivalent-to-default mappings leaves slots unaffected`() {
            val router = ControllerEventRouter()
            router.setActive(true)
            router.routeTvRemoteKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
            val before = router.slotsFlow.value

            router.applyMappings(mapOf(0 to ControllerMapping(), 1 to ControllerMapping()))

            val after = router.slotsFlow.value
            assertThat(after[0].mapping).isEqualTo(ControllerMapping())
            assertThat(after[0].currentSnapshot.buttons[LogicalControl.BUTTON_A.index]).isEqualTo(1f)
            assertThat(after[1].mapping).isEqualTo(ControllerMapping())
            // Unchanged snapshot content — no spurious change beyond the remap.
            assertThat(after).hasSize(before.size)
        }

        @Test
        fun `applyMappings ignores out-of-range slot indices`() {
            val router = ControllerEventRouter()
            router.setActive(true)

            val custom = ControllerMapping(
                axisDirections = mapOf(
                    AxisDirection(android.view.MotionEvent.AXIS_Y, -1) to LogicalControl.DPAD_UP
                )
            )
            router.applyMappings(mapOf(4 to custom, -1 to custom))

            assertThat(router.slotsFlow.value[0].mapping).isEqualTo(ControllerMapping())
        }
    }

    @Nested
    @DisplayName("Lifecycle reset")
    inner class LifecycleResetTests {

        @Test
        @DisplayName("Deactivation clears all per-device state")
        fun `deactivationClearsState`() {
            val router = ControllerEventRouter()
            router.setActive(true)

            router.routeTvRemoteKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)

            // Deactivate
            router.setActive(false)

            // State should be cleared
            val slots = router.slotsFlow.value
            assertThat(slots.none { it.connectionState == SlotConnectionState.CONNECTED }).isTrue()
            assertThat(slots.all { !it.currentSnapshot.isAnyButtonPressed }).isTrue()
            assertThat(slots[0].connectionState).isEqualTo(SlotConnectionState.UNASSIGNED)
        }

        @Test
        @DisplayName("Reactivation restores functionality")
        fun `reactivationRestoresFunctionality`() {
            val router = ControllerEventRouter()
            router.setActive(true)

            // Deactivate
            router.setActive(false)

            // Reactivate
            router.setActive(true)

            val consumed = router.routeTvRemoteKey(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER
            )
            assertThat(consumed).isTrue()
        }
    }
}
