package com.romm.androidtv.controller.config

/**
 * The nature of a controller input, used for UI rendering and capture logic.
 *
 * - [BUTTON]: digital face button, shoulder button, or stick click (L3 / R3).
 * - [DPAD]: digital directional pad direction.
 * - [ANALOG_STICK]: full analog stick axis (continuous [-1, +1] output).
 * - [TRIGGER]: unidirectional analog trigger (0..1 output).
 */
enum class InputKind {
    BUTTON,
    DPAD,
    ANALOG_STICK,
    TRIGGER,
}
