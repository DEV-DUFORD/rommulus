package com.romm.androidtv.controller.model

/**
 * Platform-neutral button identifier.
 *
 * The Android `KeyEvent` key codes are mapped to this enum at the ingestion
 * boundary (see `:app` adapters), so the shared controller model has zero
 * Android-framework dependencies. Desktop backends (e.g. JInput) map their own
 * physical codes into these same neutral keys.
 *
 * @property platformCode the Android `KeyEvent` key code this enum represents,
 *   used for serialization and Android boundary mapping.
 */
enum class NeutralKey(val platformCode: Int) {
    BUTTON_A(96),
    BUTTON_B(97),
    BUTTON_X(99),
    BUTTON_Y(100),
    BUTTON_L1(102),
    BUTTON_R1(103),
    BUTTON_L2(104),
    BUTTON_R2(105),
    BUTTON_SELECT(109),
    BUTTON_START(108),
    BUTTON_THUMBL(106),
    BUTTON_THUMBR(107),
    DPAD_UP(19),
    DPAD_DOWN(20),
    DPAD_LEFT(21),
    DPAD_RIGHT(22),

    /** Reserved for native navigation; never consumed by the router. */
    BACK(4);

    companion object {
        private val BY_PLATFORM: Map<Int, NeutralKey> = entries.associateBy { it.platformCode }

        /** Resolve the neutral key for a platform (Android) key code, or null if unknown. */
        fun fromPlatform(code: Int): NeutralKey? = BY_PLATFORM[code]
    }
}
