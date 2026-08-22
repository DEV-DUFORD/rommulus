package com.romm.desktop.controller.config

import com.romm.androidtv.controller.config.BindingAddress
import com.romm.androidtv.controller.config.CoreControlId
import com.romm.androidtv.controller.config.PhysicalBinding
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.model.NeutralKey
import com.romm.androidtv.storage.records.ControllerBindingRecord
import com.romm.desktop.player.PAD_AXIS_NAMES
import com.romm.desktop.player.PAD_BUTTON_NAMES
import com.romm.desktop.player.RetroPadControlMapping

/**
 * Pure (JVM-testable) mapping between the shared [PhysicalBinding] model and the desktop
 * [ControllerBindingRecord] rows, reusing [RetroPadControlMapping]'s persistence encoding so
 * launch serialization ([RetroPadControlMapping.toLaunchBindings]) works unchanged on rows
 * written here.
 *
 * `inputCode` encoding (desktop, platform-neutral — no Android key codes on Linux):
 * - [RetroPadControlMapping.TYPE_KEY]           → the player's `PadButton` ordinal
 *   ([PAD_BUTTON_NAMES] index)
 * - [RetroPadControlMapping.TYPE_AXIS_DIRECTION]→ the player's `PadAxis` ordinal
 *   ([PAD_AXIS_NAMES] index) + polarity
 * - [RetroPadControlMapping.TYPE_AXIS]          → the raw axis platform code (Android-origin
 *   full-analog rows; kept for cross-platform portability — the 16-slot RetroPad table cannot
 *   express them, so launch serialization omits such cores, exactly as documented upstream)
 *
 * Digital triggers reported as key presses (e.g. Xbox LT/RT on Linux arrive as
 * [NeutralKey.BUTTON_L2]/[BUTTON_R2]) have no `PadButton` name; they are expressed as the
 * unidirectional positive direction of the matching trigger pad-axis, which is the closest
 * RetroPad vocabulary equivalent. Such rows therefore decode to a
 * [PhysicalBinding.AxisDirection] rather than a [PhysicalBinding.Key]; the record itself
 * round-trips stably, which is what launch serialization consumes.
 */
object DesktopControllerBindingCodec {

    const val SCHEMA_VERSION = 1

    private val TYPE_KEY = RetroPadControlMapping.TYPE_KEY
    private val TYPE_AXIS = "AXIS"
    private val TYPE_AXIS_DIRECTION = RetroPadControlMapping.TYPE_AXIS_DIRECTION
    private val TYPE_UNMAPPED = RetroPadControlMapping.TYPE_UNMAPPED

    /** Neutral key → player pad-button wire name for physical digital controls. */
    private val NEUTRAL_KEY_TO_PAD_BUTTON: Map<NeutralKey, String> = mapOf(
        NeutralKey.BUTTON_A to "south",
        NeutralKey.BUTTON_B to "east",
        NeutralKey.BUTTON_X to "west",
        NeutralKey.BUTTON_Y to "north",
        NeutralKey.BUTTON_SELECT to "back",
        NeutralKey.BUTTON_START to "start",
        NeutralKey.BUTTON_L1 to "left_shoulder",
        NeutralKey.BUTTON_R1 to "right_shoulder",
        NeutralKey.DPAD_UP to "dpad_up",
        NeutralKey.DPAD_DOWN to "dpad_down",
        NeutralKey.DPAD_LEFT to "dpad_left",
        NeutralKey.DPAD_RIGHT to "dpad_right",
        NeutralKey.BUTTON_THUMBL to "left_stick",
        NeutralKey.BUTTON_THUMBR to "right_stick",
    )

    /** Pad-button wire name → neutral key (inverse of [NEUTRAL_KEY_TO_PAD_BUTTON]). */
    private val PAD_BUTTON_TO_NEUTRAL: Map<String, NeutralKey> =
        NEUTRAL_KEY_TO_PAD_BUTTON.entries.associateBy({ it.value }, { it.key })

    /**
     * Neutral axis → player pad-axis wire name. Xbox right-stick fallback axes (Z/RZ) share the
     * right_x/right_y pad axes with RX/RY, matching the shared NEUTRAL_AXIS_TO_CONTROL priority.
     */
    private val NEUTRAL_AXIS_TO_PAD_AXIS: Map<NeutralAxis, String> = mapOf(
        NeutralAxis.X to "left_x",
        NeutralAxis.Y to "left_y",
        NeutralAxis.RX to "right_x",
        NeutralAxis.RY to "right_y",
        NeutralAxis.Z to "right_x",
        NeutralAxis.RZ to "right_y",
        NeutralAxis.LTRIGGER to "left_trigger",
        NeutralAxis.BRAKE to "left_trigger",
        NeutralAxis.RTRIGGER to "right_trigger",
        NeutralAxis.GAS to "right_trigger",
    )

    /** Pad-axis wire name → neutral axis (canonical decode target; RX/RY/LTRIGGER/RTRIGGER). */
    private val PAD_AXIS_TO_NEUTRAL: Map<String, NeutralAxis> = mapOf(
        "left_x" to NeutralAxis.X,
        "left_y" to NeutralAxis.Y,
        "right_x" to NeutralAxis.RX,
        "right_y" to NeutralAxis.RY,
        "left_trigger" to NeutralAxis.LTRIGGER,
        "right_trigger" to NeutralAxis.RTRIGGER,
    )

    /** Encode [binding] as the override row for ([coreId], [playerIndex], [controlId]). */
    fun encode(
        coreId: String,
        playerIndex: Int,
        controlId: CoreControlId,
        binding: PhysicalBinding,
        bindingSlot: Int,
    ): ControllerBindingRecord = when (binding) {
        is PhysicalBinding.Key -> {
            val (type, inputCode, polarity) = encodeKey(binding.keyCode)
            record(coreId, playerIndex, controlId.id, bindingSlot, type, inputCode, polarity)
        }
        is PhysicalBinding.Axis ->
            record(coreId, playerIndex, controlId.id, bindingSlot, TYPE_AXIS, binding.axis, null)
        is PhysicalBinding.AxisDirection -> {
            val name = NEUTRAL_AXIS_TO_PAD_AXIS[NeutralAxis.fromPlatform(binding.axis)]
                ?: error("axis platform code ${binding.axis} has no desktop pad-axis encoding")
            record(
                coreId, playerIndex, controlId.id, bindingSlot,
                TYPE_AXIS_DIRECTION, PAD_AXIS_NAMES.indexOf(name), binding.polarity,
            )
        }
    }

    /** Encode an explicit unmapped override (mirrors Android's `ControllerBindingCodec.encodeUnmapped`). */
    fun encodeUnmapped(
        coreId: String,
        playerIndex: Int,
        address: BindingAddress,
    ): ControllerBindingRecord = record(
        coreId, playerIndex, address.controlId.id, address.slot.index, TYPE_UNMAPPED, 0, null,
    )

    /** Decode a stored row into a [PhysicalBinding], or `null` for unknown/future rows. */
    fun decode(record: ControllerBindingRecord): PhysicalBinding? {
        return when (record.bindingType) {
            TYPE_KEY -> {
                val name = PAD_BUTTON_NAMES.getOrNull(record.inputCode) ?: return null
                PAD_BUTTON_TO_NEUTRAL[name]?.let { PhysicalBinding.Key(it.platformCode) }
            }
            TYPE_AXIS -> PhysicalBinding.Axis(record.inputCode)
            TYPE_AXIS_DIRECTION -> {
                val name = PAD_AXIS_NAMES.getOrNull(record.inputCode) ?: return null
                val axis = PAD_AXIS_TO_NEUTRAL[name] ?: return null
                record.polarity?.takeIf { it == -1 || it == 1 }
                    ?.let { PhysicalBinding.AxisDirection(axis.platformCode, it) }
            }
            else -> null
        }
    }

    sealed interface DecodedOverride {
        data class Mapped(val binding: PhysicalBinding) : DecodedOverride
        data object Unmapped : DecodedOverride
    }

    fun decodeOverride(record: ControllerBindingRecord): DecodedOverride? = when (record.bindingType) {
        TYPE_UNMAPPED -> DecodedOverride.Unmapped
        else -> decode(record)?.let(DecodedOverride::Mapped)
    }

    private fun encodeKey(keyCode: Int): Triple<String, Int, Int?> {
        val neutral = NeutralKey.fromPlatform(keyCode)
            ?: error("key code $keyCode is not a known neutral key platform code")
        NEUTRAL_KEY_TO_PAD_BUTTON[neutral]?.let { name ->
            return Triple(TYPE_KEY, PAD_BUTTON_NAMES.indexOf(name), null)
        }
        // Digital triggers: the player has no trigger pad-BUTTONS, only trigger pad-AXES.
        when (neutral) {
            NeutralKey.BUTTON_L2 ->
                return Triple(TYPE_AXIS_DIRECTION, PAD_AXIS_NAMES.indexOf("left_trigger"), 1)
            NeutralKey.BUTTON_R2 ->
                return Triple(TYPE_AXIS_DIRECTION, PAD_AXIS_NAMES.indexOf("right_trigger"), 1)
            else -> Unit
        }
        error("neutral key $neutral has no desktop pad-button encoding")
    }

    private fun record(
        coreId: String,
        playerIndex: Int,
        controlId: String,
        bindingSlot: Int,
        bindingType: String,
        inputCode: Int,
        polarity: Int?,
    ) = ControllerBindingRecord(
        coreId = coreId,
        playerIndex = playerIndex,
        controlId = controlId,
        bindingSlot = bindingSlot,
        bindingType = bindingType,
        inputCode = inputCode,
        polarity = polarity,
        schemaVersion = SCHEMA_VERSION,
    )
}
