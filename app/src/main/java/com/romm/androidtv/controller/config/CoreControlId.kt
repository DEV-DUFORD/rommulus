package com.romm.androidtv.controller.config

/**
 * Stable, console-semantic identifiers for every control across all approved consoles.
 *
 * Members use console-native terminology (e.g., `N64_C_LEFT`, `GENESIS_MODE`) so the
 * user-facing label layer can present familiar names while persistence and runtime code
 * always reference these stable keys. The enum is a flat, shared inventory — every
 * member's string `id` is the **persistence-stable key** written to Room; it must never
 * be renamed or repurposed once shipped.
 */
enum class CoreControlId(val id: String) {
    // Common D-pad (shared across all consoles)
    D_PAD_UP("d_pad_up"),
    D_PAD_DOWN("d_pad_down"),
    D_PAD_LEFT("d_pad_left"),
    D_PAD_RIGHT("d_pad_right"),

    // Common face buttons (shared naming; console-specific profiles decide which exist)
    BUTTON_A("button_a"),
    BUTTON_B("button_b"),
    BUTTON_C("button_c"),
    BUTTON_X("button_x"),
    BUTTON_Y("button_y"),
    BUTTON_Z("button_z"),

    // Common menu buttons
    SELECT("select"),
    START("start"),

    // Shoulder / bumper buttons
    L1("l1"),
    R1("r1"),
    L2("l2"),
    R2("r2"),

    // Stick clicks
    L3("l3"),
    R3("r3"),

    // Analog stick axes (full-range)
    LEFT_STICK_X("left_stick_x"),
    LEFT_STICK_Y("left_stick_y"),
    RIGHT_STICK_X("right_stick_x"),
    RIGHT_STICK_Y("right_stick_y"),

    // Genesis / Sega-specific
    MODE("mode"),

    // N64 C-buttons
    N64_C_UP("n64_c_up"),
    N64_C_DOWN("n64_c_down"),
    N64_C_LEFT("n64_c_left"),
    N64_C_RIGHT("n64_c_right"),

    // N64 Z trigger
    Z("z"),

    // TurboGrafx-16 extra buttons
    BUTTON_I("button_i"),
    BUTTON_II("button_ii"),
    BUTTON_III("button_iii"),
    BUTTON_IV("button_iv"),
    BUTTON_V("button_v"),
    BUTTON_VI("button_vi"),

    // Atari 2600
    FIRE("fire"),

    // Neo Geo Pocket
    OPTION("option"),

    // Atari Lynx
    OPTION_1("option_1"),
    OPTION_2("option_2"),
    PAUSE("pause"),

    // WonderSwan
    X1("x1"),
    X2("x2"),
    Y1("y1"),
    Y2("y2"),

    // Atari 7800
    BUTTON_1("button_1"),
    BUTTON_2("button_2"),
}
