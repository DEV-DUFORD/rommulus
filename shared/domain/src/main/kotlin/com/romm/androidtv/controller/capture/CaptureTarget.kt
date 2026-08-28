package com.romm.androidtv.controller.capture

import com.romm.androidtv.controller.config.PhysicalBinding

/**
 * What kind of physical input the caller wants to capture.
 *
 * - [Digital]: a face-button press yields a [PhysicalBinding.Key]; a stick
 *   deflection yields a [PhysicalBinding.AxisDirection] (axis + polarity).
 * - [Analog]: a stick deflection yields a full [PhysicalBinding.Axis].
 * - [Trigger]: accepts either a digital trigger button as a
 *   [PhysicalBinding.Key] or a unidirectional trigger axis as a full
 *   [PhysicalBinding.Axis].
 */
sealed interface CaptureTarget {
    data object Digital : CaptureTarget
    data object Analog : CaptureTarget
    data object Trigger : CaptureTarget
}
