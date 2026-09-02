package com.romm.androidtv.controller

import com.romm.androidtv.controller.model.ControllerSlot
import com.romm.androidtv.controller.model.DeviceSignature
/**
 * Returns the effective Libretro port ordering for the given controller slots.
 *
 * Player numbers map directly to Libretro ports so intentionally empty ports
 * and manual controller assignments are preserved. Missing player numbers are
 * represented by empty slots.
 *
 * This is a pure function with no Android dependencies.
 */
fun effectiveLibretroPortOrder(slots: List<ControllerSlot>): List<ControllerSlot> =
    List(ControllerSlot.SLOT_COUNT) { portIndex ->
        val playerNumber = portIndex + 1
        slots.firstOrNull { it.playerNumber == playerNumber }
            ?: ControllerSlot(playerNumber = playerNumber)
    }

internal fun DeviceSignature?.isAndroidTvVirtualController(): Boolean =
    this == DeviceSignature.VIRTUAL_REMOTE ||
        this?.name?.startsWith("virtual-", ignoreCase = true) == true
