package com.romm.androidtv.controller.config

/**
 * A physical input binding that a user can assign to a [CoreControlId].
 *
 * Key codes use plain `Int` matching the [com.romm.androidtv.controller.model.NeutralKey]
 * platform codes and axis values match the
 * [com.romm.androidtv.controller.model.NeutralAxis] platform codes, consistent with the
 * existing [com.romm.androidtv.controller.model.ControllerMapping] and
 * [com.romm.androidtv.controller.policy.AxisMappingPolicy] conventions.
 */
sealed interface PhysicalBinding {
    /** A physical button bound by neutral key platform code. */
    data class Key(val keyCode: Int) : PhysicalBinding

    /** A full analog axis (both polarities produce continuous output). */
    data class Axis(val axis: Int) : PhysicalBinding

    /**
     * A single half-axis direction mapped to a digital target.
     * For example, the left half of AXIS_X (-1) can drive D-Pad Left
     * while the right half (+1) drives a face button.
     */
    data class AxisDirection(val axis: Int, val polarity: Int) : PhysicalBinding {
        init {
            require(polarity == -1 || polarity == 1) { "polarity must be -1 or 1, was $polarity" }
        }
    }
}
