package com.romm.androidtv.controller.config

/**
 * Pure (JVM-testable, no Android framework runtime dependency) mapping between
 * [PhysicalBinding] and its persisted [ControllerBindingEntity] row
 * (CONTROLLER_SETTINGS.md Architecture section 2).
 *
 * [encode] turns an in-memory binding into the override row the repository writes to Room.
 * [decode] reads a stored row back into a binding; unknown or future [ControllerBindingCodec]
 * `bindingType` values return `null` rather than throwing so an older/newer app version can
 * tolerate rows it no longer understands.
 */
object ControllerBindingCodec {
    const val SCHEMA_VERSION = 1

    const val TYPE_KEY = "KEY"
    const val TYPE_AXIS = "AXIS"
    const val TYPE_AXIS_DIRECTION = "AXIS_DIRECTION"
    const val TYPE_UNMAPPED = "UNMAPPED"

    /**
     * Encode [binding] as the persisted row for ([coreId], [playerIndex], [controlId]).
     *
     * - [PhysicalBinding.Key] -> ([TYPE_KEY], keyCode, null)
     * - [PhysicalBinding.Axis] -> ([TYPE_AXIS], axis, null)
     * - [PhysicalBinding.AxisDirection] -> ([TYPE_AXIS_DIRECTION], axis, polarity)
     */
    fun encode(
        coreId: String,
        playerIndex: Int,
        controlId: CoreControlId,
        binding: PhysicalBinding,
        bindingSlot: BindingSlot = BindingSlot.PRIMARY,
    ): ControllerBindingEntity = when (binding) {
        is PhysicalBinding.Key -> ControllerBindingEntity(
            coreId = coreId,
            playerIndex = playerIndex,
            controlId = controlId.id,
            bindingSlot = bindingSlot.index,
            bindingType = TYPE_KEY,
            inputCode = binding.keyCode,
            polarity = null,
            schemaVersion = SCHEMA_VERSION,
        )
        is PhysicalBinding.Axis -> ControllerBindingEntity(
            coreId = coreId,
            playerIndex = playerIndex,
            controlId = controlId.id,
            bindingSlot = bindingSlot.index,
            bindingType = TYPE_AXIS,
            inputCode = binding.axis,
            polarity = null,
            schemaVersion = SCHEMA_VERSION,
        )
        is PhysicalBinding.AxisDirection -> ControllerBindingEntity(
            coreId = coreId,
            playerIndex = playerIndex,
            controlId = controlId.id,
            bindingSlot = bindingSlot.index,
            bindingType = TYPE_AXIS_DIRECTION,
            inputCode = binding.axis,
            polarity = binding.polarity,
            schemaVersion = SCHEMA_VERSION,
        )
    }

    /**
     * Decode a stored row into a [PhysicalBinding], or `null` when [entity.bindingType] is
     * not one of the known types (unknown/future rows are ignored, never crashed on).
     */
    fun decode(entity: ControllerBindingEntity): PhysicalBinding? = when (entity.bindingType) {
        TYPE_KEY -> PhysicalBinding.Key(entity.inputCode)
        TYPE_AXIS -> PhysicalBinding.Axis(entity.inputCode)
        TYPE_AXIS_DIRECTION -> entity.polarity?.let { polarity ->
            PhysicalBinding.AxisDirection(entity.inputCode, polarity)
        }
        else -> null
    }

    sealed interface DecodedOverride {
        data class Mapped(val binding: PhysicalBinding) : DecodedOverride
        data object Unmapped : DecodedOverride
    }

    fun decodeOverride(entity: ControllerBindingEntity): DecodedOverride? = when (entity.bindingType) {
        TYPE_UNMAPPED -> DecodedOverride.Unmapped
        TYPE_KEY,
        TYPE_AXIS,
        TYPE_AXIS_DIRECTION,
        -> decode(entity)?.let(DecodedOverride::Mapped)
        else -> null
    }

    fun encodeUnmapped(
        coreId: String,
        playerIndex: Int,
        address: BindingAddress,
    ): ControllerBindingEntity = ControllerBindingEntity(
        coreId = coreId,
        playerIndex = playerIndex,
        controlId = address.controlId.id,
        bindingSlot = address.slot.index,
        bindingType = TYPE_UNMAPPED,
        inputCode = 0,
        polarity = null,
        schemaVersion = SCHEMA_VERSION,
    )
}
