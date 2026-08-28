package com.romm.androidtv.controller.adapters

import android.view.InputDevice
import com.romm.androidtv.controller.model.DeviceSignature

/**
 * Creates platform ([android.view.InputDevice])-derived [DeviceSignature]s.
 *
 * Kept in `:app` because it depends on the Android framework; the shared
 * [DeviceSignature] model itself is platform-neutral.
 */
object AndroidDeviceSignatureAdapter {

    /** Create a signature from a live [InputDevice]. */
    fun from(device: InputDevice): DeviceSignature {
        val vid = device.vendorId
        val pid = device.productId
        val sources = device.sources
        val descriptor = "vid:${String.format("%04x", vid)}-pid:${String.format("%04x", pid)}-src:$sources"
        return DeviceSignature(
            descriptor = descriptor,
            vendorId = vid,
            productId = pid,
            name = device.name
        )
    }
}
