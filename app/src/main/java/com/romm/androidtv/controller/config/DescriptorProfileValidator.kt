package com.romm.androidtv.controller.config

import android.util.Log
import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.emulation.nativehost.RetroInputDescriptor
import com.romm.androidtv.BuildConfig

/**
 * Phase 8 debug/test-time-only validator that cross-checks a static
 * [CoreControllerProfile] against the input descriptors a core actually
 * advertises natively via `RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS`.
 *
 * This catches mappings drifting after a vendored core update (e.g. Genesis
 * A/B/C or N64 C-buttons changing RetroPad targets), per CONTROLLER_SETTINGS.md
 * section 8. It is deliberately NOT wired into any live startup path — it is a
 * standalone, pure, unit-testable utility plus a thin debug-gated logging
 * wrapper.
 *
 * Note: the comparison uses only the profile's declared [CoreControlDescriptor.target]
 * (a [LogicalControl]). A profile target that has no matching native descriptor
 * for the profile's active port range is reported as a warning. Analog-stick
 * targets (e.g. pcsx/mupen64 sticks) typically have no descriptor entry because
 * cores rarely advertise ANALOG device descriptors — such warnings are expected
 * noise for axis controls and should be read as informational only.
 */
object DescriptorProfileValidator {

    // libretro.h RETRO_DEVICE_* constants.
    private const val DEVICE_JOYPAD = 1
    private const val DEVICE_ANALOG = 5

    // libretro.h RETRO_DEVICE_ID_JOYPAD_* values.
    private const val JOYPAD_B = 0
    private const val JOYPAD_Y = 1
    private const val JOYPAD_SELECT = 2
    private const val JOYPAD_START = 3
    private const val JOYPAD_UP = 4
    private const val JOYPAD_DOWN = 5
    private const val JOYPAD_LEFT = 6
    private const val JOYPAD_RIGHT = 7
    private const val JOYPAD_A = 8
    private const val JOYPAD_X = 9
    private const val JOYPAD_L = 10
    private const val JOYPAD_R = 11
    private const val JOYPAD_L2 = 12
    private const val JOYPAD_R2 = 13
    private const val JOYPAD_L3 = 14
    private const val JOYPAD_R3 = 15

    // libretro.h RETRO_DEVICE_INDEX_ANALOG_* / RETRO_DEVICE_ID_ANALOG_*.
    private const val ANALOG_INDEX_LEFT = 0
    private const val ANALOG_INDEX_RIGHT = 1
    private const val ANALOG_ID_X = 0
    private const val ANALOG_ID_Y = 1

    /** The (device, index, id) triple Libretro's input_state callback expects for a [LogicalControl]. */
    internal data class RetroTarget(val device: Int, val index: Int, val id: Int)

    /**
     * Maps a profile [LogicalControl] target to the Libretro input triple the
     * core would advertise in its descriptors (JOYPAD buttons, or ANALOG axes).
     */
    internal fun retroTarget(target: LogicalControl): RetroTarget = when (target) {
        LogicalControl.BUTTON_A -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_A)
        LogicalControl.BUTTON_B -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_B)
        LogicalControl.BUTTON_X -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_X)
        LogicalControl.BUTTON_Y -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_Y)
        LogicalControl.BUTTON_LB -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_L)
        LogicalControl.BUTTON_RB -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_R)
        LogicalControl.BUTTON_LT -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_L2)
        LogicalControl.BUTTON_RT -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_R2)
        LogicalControl.BUTTON_SELECT -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_SELECT)
        LogicalControl.BUTTON_START -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_START)
        LogicalControl.BUTTON_L3 -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_L3)
        LogicalControl.BUTTON_R3 -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_R3)
        LogicalControl.DPAD_UP -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_UP)
        LogicalControl.DPAD_DOWN -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_DOWN)
        LogicalControl.DPAD_LEFT -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_LEFT)
        LogicalControl.DPAD_RIGHT -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_RIGHT)
        LogicalControl.AXIS_LX -> RetroTarget(DEVICE_ANALOG, ANALOG_INDEX_LEFT, ANALOG_ID_X)
        LogicalControl.AXIS_LY -> RetroTarget(DEVICE_ANALOG, ANALOG_INDEX_LEFT, ANALOG_ID_Y)
        LogicalControl.AXIS_RX -> RetroTarget(DEVICE_ANALOG, ANALOG_INDEX_RIGHT, ANALOG_ID_X)
        LogicalControl.AXIS_RY -> RetroTarget(DEVICE_ANALOG, ANALOG_INDEX_RIGHT, ANALOG_ID_Y)
        LogicalControl.TRIGGER_LEFT -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_L2)
        LogicalControl.TRIGGER_RIGHT -> RetroTarget(DEVICE_JOYPAD, 0, JOYPAD_R2)
    }

    /**
     * Pure comparison: returns human-readable warning strings for every
     * profile control whose [CoreControlDescriptor.target] has no matching
     * native descriptor within the profile's active port range
     * (ports `0 until profile.playerCount`).
     *
     * Empty [descriptors] is handled gracefully: per-control validation is
     * skipped and a single "no descriptors available" note is returned (the
     * snapshot may legitimately be empty before/without a core loading its
     * descriptors, or outside a loaded session).
     *
     * Unit-testable; performs no logging and no JNI calls.
     */
    fun validate(
        coreId: String,
        descriptors: List<RetroInputDescriptor>,
        profile: CoreControllerProfile,
    ): List<String> {
        if (descriptors.isEmpty()) {
            return listOf("$coreId: no native input descriptors available to validate profile")
        }

        val activePorts = 0 until profile.playerCount
        val warnings = mutableListOf<String>()

        for (control in profile.controls) {
            val target = retroTarget(control.target)
            val advertised = descriptors.any { d ->
                d.port in activePorts &&
                    d.device == target.device &&
                    d.index == target.index &&
                    d.id == target.id
            }
            if (!advertised) {
                warnings +=
                    "$coreId: control '${control.id.id}' (${control.label}) targets " +
                    "device=${target.device} index=${target.index} id=${target.id}, " +
                    "which no native descriptor advertises for ports $activePorts"
            }
        }
        return warnings
    }

    /**
     * Thin wrapper that logs each [validate] warning via `Log.w` with the
     * established `Log.w` tag convention, but only in debug builds
     * ([BuildConfig.DEBUG] — see the same guard pattern in MainActivity.kt).
     * No-op in release builds. Returns the warnings for convenience.
     */
    fun logWarningsIfDebug(
        coreId: String,
        descriptors: List<RetroInputDescriptor>,
        profile: CoreControllerProfile,
    ): List<String> {
        val warnings = validate(coreId, descriptors, profile)
        if (BuildConfig.DEBUG) {
            warnings.forEach { Log.w(TAG, it) }
        }
        return warnings
    }

    private const val TAG = "DescriptorProfileValidator"
}
