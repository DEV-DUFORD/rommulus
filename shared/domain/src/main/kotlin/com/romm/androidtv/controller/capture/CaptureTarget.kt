package com.romm.androidtv.controller.capture

import com.romm.androidtv.controller.config.PhysicalBinding

/**
 * What kind of physical input the caller wants to capture.
 *
 * - [Digital]: a face-button press yields a [PhysicalBinding.Key]; a stick
 *   deflection yields a [PhysicalBinding.AxisDirection] (axis + polarity).
 * - [Analog]: a stick or trigger deflection yields a full
 *   [PhysicalBinding.Axis]. Trigger axes are inherently unidirectional and
 *   are captured as a full `Axis` regardless.
 */
sealed interface CaptureTarget {
    data object Digital : CaptureTarget
    data object Analog : CaptureTarget
}
