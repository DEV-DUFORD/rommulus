package com.romm.androidtv.controller

import com.romm.androidtv.controller.model.ControllerSlot
import com.romm.androidtv.controller.model.DeviceSignature
import com.romm.androidtv.controller.model.SlotConnectionState

/**
 * Returns the effective Libretro port ordering for the given controller slots.
 *
 * Connected physical controllers are compacted ahead of Android TV virtual
 * controllers, which in turn are compacted ahead of disconnected/unassigned
 * slots. Within each group, slots are sorted by [ControllerSlot.playerNumber].
 * The result is padded or truncated to [ControllerSlot.SLOT_COUNT].
 *
 * This is a pure function with no Android dependencies.
 */
fun effectiveLibretroPortOrder(slots: List<ControllerSlot>): List<ControllerSlot> {
    val ordered = slots.sortedWith(
        compareBy<ControllerSlot> { slot ->
            when {
                slot.connectionState != SlotConnectionState.CONNECTED -> 2
                slot.preferredSignature.isAndroidTvVirtualController() -> 1
                else -> 0
            }
        }.thenBy { it.playerNumber }
    )

    return ordered.take(ControllerSlot.SLOT_COUNT).let { truncated ->
        List(ControllerSlot.SLOT_COUNT) { i ->
            truncated.getOrNull(i) ?: ControllerSlot(playerNumber = i + 1)
        }
    }
}

private fun DeviceSignature?.isAndroidTvVirtualController(): Boolean =
    this == DeviceSignature.VIRTUAL_REMOTE ||
        this?.name?.startsWith("virtual-", ignoreCase = true) == true
