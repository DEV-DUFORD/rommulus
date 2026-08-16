package com.romm.androidtv.controller.policy

/**
 * Pure, framework-free source filtering policy.
 *
 * Determines whether a device's source mask indicates a controller
 * (gamepad, joystick, or DPAD-based) vs. a TV remote.
 *
 * Operates on neutral [SourceMask] bits produced at the ingestion boundary.
 */
object SourceFilterPolicy {

    /**
     * Check whether the device source mask indicates a potential controller.
     * Returns true for GAMEPAD, JOYSTICK, or DPAD sources.
     */
    fun isControllerSource(sources: Int): Boolean {
        return hasSource(sources, SourceMask.GAMEPAD) ||
            hasSource(sources, SourceMask.JOYSTICK) ||
            hasSource(sources, SourceMask.DPAD)
    }

    /**
     * Check whether a device that has DPAD source (but NOT GAMEPAD/JOYSTICK)
     * is likely a TV remote rather than a controller.
     *
     * A legitimate DPAD-only controller will have joystick axes; a TV remote
     * typically does not.
     *
     * @param sources the neutral source mask
     * @param joystickAxisCount number of joystick-capable motion ranges
     *   (X, Y, Z, RZ)
     */
    fun isTvRemote(sources: Int, joystickAxisCount: Int): Boolean {
        val hasGamepad = hasSource(sources, SourceMask.GAMEPAD)
        val hasJoystick = hasSource(sources, SourceMask.JOYSTICK)

        // GAMEPAD or JOYSTICK source always means controller
        if (hasGamepad || hasJoystick) return false

        // DPAD-only device: check for joystick axes to distinguish
        // a retro gamepad from a TV remote
        if (joystickAxisCount > 0) return false

        // No controller indicators — treat as remote
        return true
    }

    private fun hasSource(sources: Int, source: Int): Boolean =
        (sources and source) == source
}
