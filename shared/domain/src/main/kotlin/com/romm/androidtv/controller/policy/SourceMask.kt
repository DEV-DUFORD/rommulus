package com.romm.androidtv.controller.policy

/**
 * Platform-neutral bit mask identifying controller source capabilities.
 *
 * The router maps platform (Android `InputDevice`) source masks into these
 * neutral bits at the ingestion boundary, so the shared policy layer has zero
 * Android-framework dependencies. Desktop backends map their own source
 * semantics into the same bits.
 */
object SourceMask {
    const val GAMEPAD = 0x00000400
    const val JOYSTICK = 0x00000002
    const val DPAD = 0x00000001
}
