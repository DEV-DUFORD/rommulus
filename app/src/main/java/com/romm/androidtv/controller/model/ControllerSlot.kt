package com.romm.androidtv.controller.model

/**
 * Connection state of a physical device to its logical slot.
 */
enum class SlotConnectionState {
    /** No device assigned to this slot. */
    UNASSIGNED,

    /** A device is assigned and currently connected. */
    CONNECTED,

    /** A device was assigned but is now disconnected (mapping preserved). */
    DISCONNECTED
}

/**
 * One logical player slot. Stable across device hot-plug cycles.
 *
 * Each slot holds:
 * - An optional preferred [DeviceSignature] for reconnect heuristics.
 * - The current [SlotConnectionState].
 * - A [ControllerMapping] (physical -> virtual).
 * - The live [GamepadSnapshot] produced by the router.
 */
data class ControllerSlot(
    val playerNumber: Int,
    val preferredSignature: DeviceSignature? = null,
    val connectionState: SlotConnectionState = SlotConnectionState.UNASSIGNED,
    val mapping: ControllerMapping = ControllerMapping(),
    val currentSnapshot: GamepadSnapshot = GamepadSnapshot.EMPTY
) {
    init {
        require(playerNumber in 1..SLOT_COUNT) { "playerNumber must be 1..$SLOT_COUNT, was $playerNumber" }
    }

    /** True when a device is assigned AND connected. */
    val isActive: Boolean
        get() = connectionState == SlotConnectionState.CONNECTED

    /** Assign a device signature to this slot. Returns a new slot. */
    fun assign(signature: DeviceSignature): ControllerSlot =
        copy(
            preferredSignature = signature,
            connectionState = SlotConnectionState.CONNECTED
        )

    /** Mark the device as disconnected (preserves mapping). Returns a new slot. */
    fun disconnect(): ControllerSlot =
        copy(
            connectionState = SlotConnectionState.DISCONNECTED,
            currentSnapshot = GamepadSnapshot.EMPTY
        )

    /** Reconnect the assigned device. Returns a new slot. */
    fun reconnect(): ControllerSlot =
        copy(
            connectionState = SlotConnectionState.CONNECTED
        )

    /** Update the live snapshot. Returns a new slot. */
    fun updateSnapshot(snapshot: GamepadSnapshot): ControllerSlot =
        copy(currentSnapshot = snapshot)

    /** Replace the mapping. Takes effect immediately. */
    fun remap(newMapping: ControllerMapping): ControllerSlot =
        copy(mapping = newMapping)

    companion object {
        /** Total number of slots: exactly 4 physical controllers. Browser W3C contract is 4 slots. */
        const val SLOT_COUNT = 4

        /** Create the initial four empty slots. */
        fun createAllSlots(): List<ControllerSlot> =
            listOf(1, 2, 3, 4).map { n ->
                ControllerSlot(playerNumber = n)
            }
    }
}
