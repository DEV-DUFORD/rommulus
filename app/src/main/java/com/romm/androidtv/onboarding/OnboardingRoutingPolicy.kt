package com.romm.androidtv.onboarding

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.network.RommOrigin

/**
 * Initial-route decision for the app on cold start (spec section 5.1).
 *
 * Pure and fully JVM-testable: no Android framework objects, no I/O. Decides
 * whether the app should launch the first-run [AppMode.ONBOARDING] flow or go
 * straight to [AppMode.MAIN], given the persisted session [SessionStore.Record],
 * the currently configured profile origin, and whether the caller already found
 * a matching decryptable durable client token.
 *
 * Returns [AppMode.MAIN] only when EVERY gate passes:
 *  - a durable client token matching the profile exists ([hasMatchingToken]), and
 *  - a coherent session record exists (non-blank origin, non-blank username), and
 *  - that record's origin is canonically the same origin + same base path as the
 *    active profile origin.
 *
 * Any missing/incoherent/mismatched fact yields [AppMode.ONBOARDING].
 */
object OnboardingRoutingPolicy {

    /** Top-level launch mode (reused by Phase 5's MainActivity wiring). */
    enum class AppMode { ONBOARDING, MAIN }

    /**
     * @param record          The persisted [SessionStore.Record], or null.
     * @param profileOrigin   The currently configured profile origin, or null.
     * @param hasMatchingToken True when the caller already located a decryptable
     *                         durable client token for [profileOrigin].
     */
    fun decide(
        record: SessionStore.Record?,
        profileOrigin: String?,
        hasMatchingToken: Boolean,
    ): AppMode {
        if (record == null) return AppMode.ONBOARDING
        if (record.origin.isBlank()) return AppMode.ONBOARDING
        if (record.username.isNullOrBlank()) return AppMode.ONBOARDING
        if (profileOrigin.isNullOrBlank()) return AppMode.ONBOARDING

        val recordParsed = RommOrigin.parse(record.origin) ?: return AppMode.ONBOARDING
        val profileParsed = RommOrigin.parse(profileOrigin) ?: return AppMode.ONBOARDING
        val sameOrigin = recordParsed.isSameOrigin(profileParsed) &&
            recordParsed.path == profileParsed.path
        if (!sameOrigin) return AppMode.ONBOARDING

        // A kiosk (anonymous read-only) session is coherent without a durable client token —
        // kiosk servers never issue tokens, so MAIN must not require one here.
        if (record.kioskMode) return AppMode.MAIN

        return if (hasMatchingToken) AppMode.MAIN else AppMode.ONBOARDING
    }
}
