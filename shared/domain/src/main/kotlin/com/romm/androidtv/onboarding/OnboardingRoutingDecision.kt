package com.romm.androidtv.onboarding

import com.romm.androidtv.network.RommOrigin

/**
 * Pure initial-route decision for the app on cold start (spec section 5.1),
 * shared by the Android and Linux-desktop products.
 *
 * Fully framework-free and JVM-testable: no Android objects, no I/O, no module
 * dependencies. Decides whether the app should launch the first-run
 * [AppMode.ONBOARDING] flow or go straight to [AppMode.MAIN], given the persisted
 * session facts, the currently configured profile origin, and whether the caller
 * already found a matching decryptable durable client token.
 *
 * The Android `OnboardingRoutingPolicy` (in `:app`) is a thin wrapper that adapts
 * `SessionStore.Record` to these primitive facts and delegates here; the desktop
 * coordinator calls this object directly.
 *
 * Returns [AppMode.MAIN] only when EVERY gate passes:
 *  - a durable client token matching the profile exists ([hasMatchingToken]), and
 *  - a coherent session record exists (non-blank origin, non-blank username), and
 *  - that record's origin is canonically the same origin + same base path as the
 *    active profile origin.
 *
 * Any missing/incoherent/mismatched fact yields [AppMode.ONBOARDING].
 */
object OnboardingRoutingDecision {

    /** Top-level launch mode. */
    enum class AppMode { ONBOARDING, MAIN }

    /**
     * @param recordOrigin    The persisted session record origin, or null.
     * @param recordUsername  The persisted session username, or null/blank.
     * @param recordKioskMode True when the session is an anonymous read-only (kiosk) session.
     * @param profileOrigin   The currently configured profile origin, or null.
     * @param hasMatchingToken True when the caller already located a decryptable
     *                         durable client token for [profileOrigin].
     */
    fun decide(
        recordOrigin: String?,
        recordUsername: String?,
        recordKioskMode: Boolean,
        profileOrigin: String?,
        hasMatchingToken: Boolean,
    ): AppMode {
        if (recordOrigin == null) return AppMode.ONBOARDING
        if (recordOrigin.isBlank()) return AppMode.ONBOARDING
        if (recordUsername.isNullOrBlank()) return AppMode.ONBOARDING
        if (profileOrigin.isNullOrBlank()) return AppMode.ONBOARDING

        val recordParsed = RommOrigin.parse(recordOrigin) ?: return AppMode.ONBOARDING
        val profileParsed = RommOrigin.parse(profileOrigin) ?: return AppMode.ONBOARDING
        val sameOrigin = recordParsed.isSameOrigin(profileParsed) &&
            recordParsed.path == profileParsed.path
        if (!sameOrigin) return AppMode.ONBOARDING

        // A kiosk (anonymous read-only) session is coherent without a durable client token —
        // kiosk servers never issue tokens, so MAIN must not require one here.
        if (recordKioskMode) return AppMode.MAIN

        return if (hasMatchingToken) AppMode.MAIN else AppMode.ONBOARDING
    }
}
