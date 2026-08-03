package com.romm.androidtv.controller.config

import com.romm.androidtv.controller.model.ControllerSlot

/**
 * Per-player default or overridden physical binding for each console control.
 *
 * Maps every [CoreControlId] that a core profile declares to up to two
 * [PhysicalBinding] values. Only overrides from user edits are persisted; catalog
 * defaults are merged in at read time.
 */
data class PlayerControllerConfig(
    val bindings: Map<CoreControlId, ControlBindings> = emptyMap()
) {
    /** Retrieve the primary physical binding for compatibility with single-binding callers. */
    operator fun get(controlId: CoreControlId): PhysicalBinding? =
        bindings[controlId]?.primary

    fun get(controlId: CoreControlId, slot: BindingSlot): PhysicalBinding? =
        bindings[controlId]?.get(slot)

    companion object {
        /** Maximum number of player configs allowed per profile. */
        val MAX_PLAYERS: Int = ControllerSlot.SLOT_COUNT
    }
}
