package com.romm.androidtv.storage.records

/**
 * Binding slot constants for controller bindings. Mirrors the Android Room schema
 * (ControllerBindingEntity) where bindingSlot is 0=primary, 1=secondary.
 */
object BindingSlots {
    const val PRIMARY = 0
    const val SECONDARY = 1
}

/**
 * One persisted binding override, persistence-neutral mirror of the Android Room
 * schema (ControllerBindingEntity). Composite key: coreId/playerIndex/controlId/bindingSlot.
 */
data class ControllerBindingRecord(
    val coreId: String,
    val playerIndex: Int,
    val controlId: String,
    val bindingSlot: Int,
    val bindingType: String,
    val inputCode: Int,
    val polarity: Int? = null,
    val schemaVersion: Int = 1,
)
