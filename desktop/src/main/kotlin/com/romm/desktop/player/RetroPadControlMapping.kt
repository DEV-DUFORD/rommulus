package com.romm.desktop.player

import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.controller.model.NEUTRAL_KEY_TO_CONTROL
import com.romm.androidtv.controller.model.NeutralKey
import com.romm.androidtv.controller.config.CoreControllerProfiles
import com.romm.androidtv.controller.config.CoreControlId
import com.romm.androidtv.controller.config.isPauseMenuControl
import com.romm.androidtv.storage.records.BindingSlots
import com.romm.androidtv.storage.records.ControllerBindingRecord

/**
 * Bridges the player's 16 RetroPad slots to the shared controller model (LINUX_X64.md §11.9).
 *
 * The desktop player applies ONE global binding table to every port, so ingestion stores each
 * device's table under [PLAYER_INDEX] and launch serialization reads that same bucket back.
 * Every slot routes through the shared [NEUTRAL_KEY_TO_CONTROL] mapping (all 16 RetroPad slots
 * have a neutral key), and the persisted [ControllerBindingRecord.controlId] values are
 * IDENTICAL to Android's `CoreControlId` persistence keys (`button_a`, `l1`, `d_pad_up`, ...)
 * so bindings stay portable between platforms.
 *
 * `inputCode` encoding (desktop, platform-neutral — no Android key codes on Linux):
 * - [TYPE_KEY]           → the player's `PadButton` ordinal ([PAD_BUTTON_NAMES] index)
 * - [TYPE_AXIS_DIRECTION]→ the player's `PadAxis` ordinal ([PAD_AXIS_NAMES] index) + polarity
 */
object RetroPadControlMapping {

    /** The desktop player applies one global table to all ports; bindings live in this bucket. */
    const val PLAYER_INDEX = 0

    // Persistence-stable bindingType constants — IDENTICAL to Android's
    // ControllerBindingCodec so rows written on either platform decode on the other.
    const val TYPE_KEY = "KEY"
    const val TYPE_AXIS_DIRECTION = "AXIS_DIRECTION"
    const val TYPE_UNMAPPED = "UNMAPPED"

    /** Player RetroPad slot wire name → neutral key (all 16 slots are covered). */
    private val SLOT_TO_NEUTRAL_KEY: Map<String, NeutralKey> = mapOf(
        "a" to NeutralKey.BUTTON_A,
        "b" to NeutralKey.BUTTON_B,
        "x" to NeutralKey.BUTTON_X,
        "y" to NeutralKey.BUTTON_Y,
        "select" to NeutralKey.BUTTON_SELECT,
        "start" to NeutralKey.BUTTON_START,
        "left_shoulder" to NeutralKey.BUTTON_L1,
        "right_shoulder" to NeutralKey.BUTTON_R1,
        "dpad_up" to NeutralKey.DPAD_UP,
        "dpad_down" to NeutralKey.DPAD_DOWN,
        "dpad_left" to NeutralKey.DPAD_LEFT,
        "dpad_right" to NeutralKey.DPAD_RIGHT,
        "left_trigger" to NeutralKey.BUTTON_L2,
        "right_trigger" to NeutralKey.BUTTON_R2,
        "left_stick" to NeutralKey.BUTTON_THUMBL,
        "right_stick" to NeutralKey.BUTTON_THUMBR,
    )

    /** Slot wire name → shared [LogicalControl], via the shared neutral-key mapping. */
    val SLOT_TO_CONTROL: Map<String, LogicalControl> =
        SLOT_TO_NEUTRAL_KEY.mapValues { (_, key) -> NEUTRAL_KEY_TO_CONTROL.getValue(key) }

    /**
     * Persistence-stable controlId per [LogicalControl] — byte-identical to the Android
     * `CoreControlId.id` values for the same controls. Must never be renamed once shipped.
     */
    val CONTROL_TO_ID: Map<LogicalControl, String> = mapOf(
        LogicalControl.BUTTON_A to "button_a",
        LogicalControl.BUTTON_B to "button_b",
        LogicalControl.BUTTON_X to "button_x",
        LogicalControl.BUTTON_Y to "button_y",
        LogicalControl.BUTTON_LB to "l1",
        LogicalControl.BUTTON_RB to "r1",
        LogicalControl.BUTTON_SELECT to "select",
        LogicalControl.BUTTON_START to "start",
        LogicalControl.DPAD_UP to "d_pad_up",
        LogicalControl.DPAD_DOWN to "d_pad_down",
        LogicalControl.DPAD_LEFT to "d_pad_left",
        LogicalControl.DPAD_RIGHT to "d_pad_right",
        LogicalControl.BUTTON_LT to "l2",
        LogicalControl.BUTTON_RT to "r2",
        LogicalControl.BUTTON_L3 to "l3",
        LogicalControl.BUTTON_R3 to "r3",
    )

    /** Slot wire name → persistence-stable controlId. */
    val SLOT_TO_CONTROL_ID: Map<String, String> =
        SLOT_TO_CONTROL.mapValues { CONTROL_TO_ID.getValue(it.value) }

    /**
     * One sidecar/request device (16-slot table) → shared binding records for [coreId].
     * Every slot produces a row — including UNMAPPED rows for unbound slots, so the stored
     * table is complete and launch serialization can reconstruct it exactly.
     */
    fun toRecords(coreId: String, device: ControllerBindingDevice): List<ControllerBindingRecord> =
        buildList {
            device.bindings.forEach { slot ->
                add(toRecord(coreId, coreControlIdForSlot(coreId, slot.slot).id, slot, BindingSlots.PRIMARY))
            }
            device.secondaryBindings?.forEach { slot ->
                add(toRecord(coreId, coreControlIdForSlot(coreId, slot.slot).id, slot, BindingSlots.SECONDARY))
            }
        }

    private fun toRecord(
        coreId: String,
        controlId: String,
        slot: PlayerSlotBinding,
        bindingSlot: Int,
    ): ControllerBindingRecord {
            return when (slot.type) {
                PlayerBindingType.UNBOUND -> ControllerBindingRecord(
                    coreId = coreId,
                    playerIndex = PLAYER_INDEX,
                    controlId = controlId,
                    bindingSlot = bindingSlot,
                    bindingType = TYPE_UNMAPPED,
                    inputCode = 0,
                    polarity = null,
                )
                PlayerBindingType.BUTTON -> ControllerBindingRecord(
                    coreId = coreId,
                    playerIndex = PLAYER_INDEX,
                    controlId = controlId,
                    bindingSlot = bindingSlot,
                    bindingType = TYPE_KEY,
                    inputCode = PAD_BUTTON_NAMES.indexOf(checkNotNull(slot.button)),
                    polarity = null,
                )
                PlayerBindingType.AXIS_DIRECTION -> ControllerBindingRecord(
                    coreId = coreId,
                    playerIndex = PLAYER_INDEX,
                    controlId = controlId,
                    bindingSlot = bindingSlot,
                    bindingType = TYPE_AXIS_DIRECTION,
                    inputCode = PAD_AXIS_NAMES.indexOf(checkNotNull(slot.axis)),
                    polarity = checkNotNull(slot.polarity),
                )
            }
        }

    /**
     * Stored records → the 16-slot table for a v2 request [ControllerBindings], or null when
     * the stored table is incomplete/invalid (the caller then omits the field and the player
     * keeps its defaults — a request must never be written with a partial table).
     */
    fun toLaunchBindings(records: List<ControllerBindingRecord>): ControllerBindings? {
        if (records.isEmpty()) return null
        val byAddress = records.associateBy { it.controlId to it.bindingSlot }
        val coreId = records.first().coreId

        fun slotsFor(bindingSlot: Int, required: Boolean): List<PlayerSlotBinding>? {
            val slots = mutableListOf<PlayerSlotBinding>()
            for (slotName in RETRO_PAD_SLOT_NAMES) {
                val record = byAddress[coreControlIdForSlot(coreId, slotName).id to bindingSlot]
                    ?: if (required) return null else return null
            when (record.bindingType) {
                TYPE_UNMAPPED -> slots += PlayerSlotBinding(slotName, PlayerBindingType.UNBOUND)
                TYPE_KEY -> {
                    val button = PAD_BUTTON_NAMES.getOrNull(record.inputCode) ?: return null
                    slots += PlayerSlotBinding(slotName, PlayerBindingType.BUTTON, button = button)
                }
                TYPE_AXIS_DIRECTION -> {
                    val axis = PAD_AXIS_NAMES.getOrNull(record.inputCode) ?: return null
                    val polarity = record.polarity?.takeIf { it == -1 || it == 1 } ?: return null
                    slots += PlayerSlotBinding(slotName, PlayerBindingType.AXIS_DIRECTION, axis = axis, polarity = polarity)
                }
                // A full-analog AXIS row (Android-only) or an unknown type cannot be expressed
                // in a digital RetroPad slot: omit the field rather than fabricate semantics.
                else -> return null
            }
            }
            return slots
        }
        val slots = slotsFor(BindingSlots.PRIMARY, required = true) ?: return null
        val secondarySlots = if (records.any { it.bindingSlot == BindingSlots.SECONDARY }) {
            slotsFor(BindingSlots.SECONDARY, required = true) ?: return null
        } else {
            null
        }

        // The desktop store keys bindings by core, not device: serialize ONE entry with an
        // empty guid/identity ("apply to every controller") — the player seeds its single
        // global table from the first device entry.
        val device = ControllerBindingDevice(
            guid = "",
            identity = ControllerBindingIdentity(vendorId = null, productId = null, descriptor = ""),
            bindings = slots,
            secondaryBindings = secondarySlots,
        )
        return ControllerBindings(listOf(device))
    }

    internal fun coreControlIdForSlot(coreId: String, slotName: String): CoreControlId {
        val target = SLOT_TO_CONTROL.getValue(slotName)
        return CoreControllerProfiles.byCoreId(coreId)
            ?.controls
            ?.firstOrNull { !it.id.isPauseMenuControl && it.target == target }
            ?.id
            ?: CoreControlId.entries.first { it.id == SLOT_TO_CONTROL_ID.getValue(slotName) }
    }
}
