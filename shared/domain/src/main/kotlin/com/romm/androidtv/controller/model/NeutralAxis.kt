package com.romm.androidtv.controller.model

/**
 * Platform-neutral analog axis identifier.
 *
 * The Android `MotionEvent` axis constants are mapped to this enum at the
 * ingestion boundary (see `:app` adapters), so the shared controller model has
 * zero Android-framework dependencies. Desktop backends (e.g. JInput) map their
 * own physical axes into these same neutral axes.
 *
 * @property platformCode the Android `MotionEvent` axis constant this enum
 *   represents, used for serialization and Android boundary mapping.
 */
enum class NeutralAxis(val platformCode: Int) {
    X(0),
    Y(1),
    Z(11),
    RX(12),
    RY(13),
    RZ(14),
    LTRIGGER(17),
    RTRIGGER(18),
    GAS(22),
    BRAKE(23);

    companion object {
        private val BY_PLATFORM: Map<Int, NeutralAxis> = entries.associateBy { it.platformCode }

        /** Resolve the neutral axis for a platform (Android) axis constant, or null if unknown. */
        fun fromPlatform(code: Int): NeutralAxis? = BY_PLATFORM[code]
    }
}
