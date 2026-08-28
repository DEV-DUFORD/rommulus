package com.romm.androidtv.controller.adapters

import kotlin.math.abs

/**
 * Device-specific corrections for controllers whose Android-reported input
 * characteristics do not adequately describe their real hardware behavior.
 */
internal object AndroidControllerInputProfiles {
    private const val HYPERKIN_VENDOR_ID = 0x2e24
    private const val HYPERKIN_PRODUCT_ID = 0x0bff
    private const val HYPERKIN_STICK_DEADZONE = 0.30f

    fun applyDeadzone(
        vendorId: Int,
        productId: Int,
        axis: Int,
        normalizedValue: Float,
    ): Float {
        val isHyperkinN64Adapter =
            vendorId == HYPERKIN_VENDOR_ID && productId == HYPERKIN_PRODUCT_ID
        val isPrimaryStickAxis = axis == 0 || axis == 1
        return if (
            isHyperkinN64Adapter &&
            isPrimaryStickAxis &&
            abs(normalizedValue) < HYPERKIN_STICK_DEADZONE
        ) {
            0f
        } else {
            normalizedValue
        }
    }
}
