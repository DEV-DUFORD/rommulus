package com.romm.androidtv.controller.config

import com.romm.androidtv.controller.model.ControllerSlot

/**
 * Per-player default or overridden physical binding for each console control.
 *
 * Maps every [CoreControlId] that a core profile declares to a concrete
 * [PhysicalBinding]. Only overrides from user edits are persisted; catalog
 * defaults are merged in at read time.
 */
data class PlayerControllerConfig(
    val bindings: Map<CoreControlId, PhysicalBinding> = emptyMap()
) {
    /** Retrieve the physical binding for a given control ID. */
    operator fun get(controlId: CoreControlId): PhysicalBinding? =
        bindings[controlId]

    companion object {
        /** Maximum number of player configs allowed per profile. */
        val MAX_PLAYERS: Int = ControllerSlot.SLOT_COUNT
    }
}
