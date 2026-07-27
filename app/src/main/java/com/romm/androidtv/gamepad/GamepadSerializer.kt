package com.romm.androidtv.gamepad

import com.romm.androidtv.controller.model.ControllerSlot
import com.romm.androidtv.controller.model.GamepadSnapshot
import com.romm.androidtv.controller.model.SlotConnectionState

/**
 * Serializes four [ControllerSlot] states into a compact JSON array
 * for delivery to injected JavaScript via WebView.evaluateJavascript.
 * Slots 0-3 are physical controllers (W3C Gamepad API standard).
 *
 * W3C Gamepad API compliance:
 * - 16 buttons (indices 0-15). Triggers mapped to button indices 6 (LT) and 7 (RT).
 * - 4 axes only (left stick X/Y at indices 0/1, right stick X/Y at indices 2/3).
 *   The native model retains 6 axes internally; triggers are remapped to buttons here.
 *
 * Strict validation:
 * - All numeric values are clamped to finite ranges.
 * - Buttons: 0..1, Axes: -1..1
 * - Payload size is capped to prevent OOM on the JS side.
 */
object GamepadSerializer {

    /** Maximum serialized JSON payload size in bytes. Prevents runaway payloads. */
    const val MAX_PAYLOAD_BYTES = 4096

    // W3C Gamepad API: triggers are buttons at indices 6 (LT) and 7 (RT).
    private const val W3C_BUTTON_LT = 6
    private const val W3C_BUTTON_RT = 7

    // Native model: triggers live at axis indices 4 (LT) and 5 (RT).
    private const val NATIVE_AXIS_LT = 4
    private const val NATIVE_AXIS_RT = 5

    // W3C standard exposes exactly 4 axes (two analog sticks).
    private const val W3C_NUM_AXES = 4

    /**
     * Serialize four slots into a JSON array string for `__rommUpdateGamepads`.
     * Slots 0-3 are physical controllers.
     *
     * Returns null if the serialized output exceeds [MAX_PAYLOAD_BYTES].
     */
    fun serializeSlots(slots: List<ControllerSlot>): String? {
        if (slots.size != 4) return null

        val sb = StringBuilder("[")
        for ((index, slot) in slots.withIndex()) {
            if (index > 0) sb.append(",")
            serializeSlot(slot, index, sb)
        }
        sb.append("]")

        val json = sb.toString()
        if (json.length > MAX_PAYLOAD_BYTES) {
            return null // Payload too large — skip this update
        }
        return json
    }

    /**
     * Serialize a single slot. Only slots 0-3 are serialized.
     */
    private fun serializeSlot(slot: ControllerSlot, index: Int, sb: StringBuilder) {
        when (slot.connectionState) {
            SlotConnectionState.CONNECTED -> {
                val snap = slot.currentSnapshot
                sb.append("{\"index\":$index,\"connected\":true,\"id\":\"RomM Virtual Gamepad ")
                    .append(index + 1).append("\",\"buttons\":[")
                serializeW3cButtons(snap, sb)
                sb.append("],\"axes\":[")
                serializeW3cAxes(snap.axes, sb)
                sb.append("]}")
            }
            else -> {
                // Disconnected or unassigned — signal null slot
                sb.append("null")
            }
        }
    }

    /**
     * Serialize buttons in W3C order: standard buttons plus triggers as buttons 6/7.
     *
     * Layout (W3C Gamepad Standard Mapping, 16-button subset):
     * A0 B1 X2 Y3 LB4 RB5 LT6 RT7 Select8 Start9 L3=10 R3=11 Dpad Up12 Down13 Left14 Right15
     */
    private fun serializeW3cButtons(snap: GamepadSnapshot, sb: StringBuilder) {
        val buttons = snap.buttons
        val axes = snap.axes

        // Extract trigger values from native axes and clamp to button range [0, 1].
        val ltValue = clampFinite(
            if (axes.size > NATIVE_AXIS_LT) axes[NATIVE_AXIS_LT] else 0f,
            0f, 1f
        )
        val rtValue = clampFinite(
            if (axes.size > NATIVE_AXIS_RT) axes[NATIVE_AXIS_RT] else 0f,
            0f, 1f
        )

        for (i in 0 until 16) {
            if (i > 0) sb.append(",")
            val value = when (i) {
                W3C_BUTTON_LT -> ltValue
                W3C_BUTTON_RT -> rtValue
                else -> {
                    val raw = if (i < buttons.size) buttons[i] else 0f
                    clampFinite(raw, 0f, 1f)
                }
            }
            sb.append(formatFloat(value))
        }
    }

    /**
     * Serialize exactly 4 axes (W3C standard: left/right stick X/Y).
     * Native axes 4/5 (triggers) are NOT included; they are remapped to buttons.
     */
    private fun serializeW3cAxes(axes: FloatArray, sb: StringBuilder) {
        for (i in 0 until W3C_NUM_AXES) {
            if (i > 0) sb.append(",")
            val raw = if (i < axes.size) axes[i] else 0f
            val clamped = clampFinite(raw, -1f, 1f)
            sb.append(formatFloat(clamped))
        }
    }

    /**
     * Clamp a float to [min, max], treating NaN and Infinity as zero.
     */
    private fun clampFinite(value: Float, min: Float, max: Float): Float {
        return if (!value.isFinite()) 0f else value.coerceIn(min, max)
    }

    /**
     * Format a float for JSON. Uses integer representation for whole numbers
     * to reduce payload size.
     */
    private fun formatFloat(value: Float): String {
        return if (value == value.toInt().toFloat() && value.isFinite()) {
            value.toInt().toString()
        } else {
            // Limit decimal places to avoid excessive precision
            "%.4f".format(value).trimEnd('0').let {
                if (it.endsWith('.')) it.trimEnd('.') else it
            }
        }
    }
}
