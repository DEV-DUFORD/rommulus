package com.romm.androidtv.controller.config

import androidx.room.Entity

/**
 * One persisted binding override for a single [coreId], [playerIndex], [controlId] tuple
 * (CONTROLLER_SETTINGS.md Architecture section 2). Only user overrides are stored; catalog
 * defaults are merged over them at read time by the repository (a separate task).
 *
 * [bindingType] is one of [ControllerBindingCodec.TYPE_KEY], [ControllerBindingCodec.TYPE_AXIS]
 * or [ControllerBindingCodec.TYPE_AXIS_DIRECTION]. [polarity] is non-null **only** for
 * [ControllerBindingCodec.TYPE_AXIS_DIRECTION] and must then be exactly -1 or +1; for KEY and
 * AXIS rows it must be null. [inputCode] carries the Android keyCode or [android.view.MotionEvent]
 * axis constant depending on [bindingType].
 *
 * The row is keyed by the same triple the runtime uses so main-process edits and the separate
 * `:emulation` process observe identical rows through multi-instance invalidation.
 */
@Entity(
    tableName = "controller_bindings",
    primaryKeys = ["coreId", "playerIndex", "controlId"],
)
data class ControllerBindingEntity(
    /** Stable core ID (e.g., `snes9x`). */
    val coreId: String,
    /** Zero-based Libretro player port. */
    val playerIndex: Int,
    /** Persistence-stable [CoreControlId] string key. */
    val controlId: String,
    /** One of [ControllerBindingCodec.TYPE_KEY], [ControllerBindingCodec.TYPE_AXIS], [ControllerBindingCodec.TYPE_AXIS_DIRECTION]. */
    val bindingType: String,
    /** Android keyCode or [android.view.MotionEvent] axis constant. */
    val inputCode: Int,
    /** -1/+1 for [ControllerBindingCodec.TYPE_AXIS_DIRECTION] rows only, otherwise null. */
    val polarity: Int?,
    /** Codec schema version, for future migration. */
    val schemaVersion: Int,
) {
    init {
        require(coreId.isNotBlank()) { "coreId must not be blank" }
        require(controlId.isNotBlank()) { "controlId must not be blank" }
        require(playerIndex >= 0) { "playerIndex must be >= 0, was $playerIndex" }
        when (bindingType) {
            ControllerBindingCodec.TYPE_KEY,
            ControllerBindingCodec.TYPE_AXIS,
            -> {
                require(polarity == null) {
                    "polarity must be null for $bindingType, was $polarity"
                }
            }
            ControllerBindingCodec.TYPE_AXIS_DIRECTION -> {
                require(polarity == -1 || polarity == 1) {
                    "polarity must be -1 or +1 for $bindingType, was $polarity"
                }
            }
        }
    }
}
