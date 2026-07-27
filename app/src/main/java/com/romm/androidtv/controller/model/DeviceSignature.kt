package com.romm.androidtv.controller.model

import android.view.InputDevice

/**
 * Immutable fingerprint for a physical input device, independent of transient
 * [android.view.InputDevice.id] which changes across reboots and hot-plug cycles.
 *
 * Two identical controllers share the same signature; session-level assignment
 * order disambiguates them.
 *
 * Reconnect identity is based on VID+PID+sources (the descriptor), NOT the
 * mutable display name. This ensures that firmware updates or OS-level name
 * changes do not break slot reassignment.
 */
data class DeviceSignature(
    val descriptor: String,
    val vendorId: Int,
    val productId: Int,
    val name: String
) {
    /**
     * Reconnect identity key: VID + PID + source mask.
     * Excludes the mutable display name so that firmware updates or
     * OS-level device-name changes do not break slot reassignment.
     */
    val reconnectKey: String
        get() = descriptor

    /**
     * Check whether this signature represents the same physical controller
     * as [other], ignoring transient display-name differences.
     */
    fun matchesReconnect(other: DeviceSignature): Boolean =
        reconnectKey == other.reconnectKey

    companion object {
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

        /** Virtual device signature for the TV remote -> gamepad translation slot. */
        val VIRTUAL_REMOTE = DeviceSignature(
            descriptor = "virtual:remote",
            vendorId = 0,
            productId = 0,
            name = "TV Remote (Virtual Gamepad)"
        )
    }
}
