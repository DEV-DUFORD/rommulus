package com.romm.androidtv.controller.config

/** One of the two independently editable physical bindings for a console control. */
enum class BindingSlot(val index: Int, val displayName: String) {
    PRIMARY(0, "Primary"),
    SECONDARY(1, "Secondary");

    companion object {
        fun fromIndex(index: Int): BindingSlot? = entries.firstOrNull { it.index == index }
    }
}

data class ControlBindings(
    val primary: PhysicalBinding? = null,
    val secondary: PhysicalBinding? = null,
) {
    operator fun get(slot: BindingSlot): PhysicalBinding? = when (slot) {
        BindingSlot.PRIMARY -> primary
        BindingSlot.SECONDARY -> secondary
    }

    fun with(slot: BindingSlot, binding: PhysicalBinding?): ControlBindings = when (slot) {
        BindingSlot.PRIMARY -> copy(primary = binding)
        BindingSlot.SECONDARY -> copy(secondary = binding)
    }

    fun entries(): List<Pair<BindingSlot, PhysicalBinding>> = buildList {
        primary?.let { add(BindingSlot.PRIMARY to it) }
        secondary?.let { add(BindingSlot.SECONDARY to it) }
    }
}

data class BindingAddress(
    val controlId: CoreControlId,
    val slot: BindingSlot,
)
