package com.romm.androidtv.onboarding

import com.romm.androidtv.auth.SessionStore

/**
 * Android adapter for the shared [OnboardingRoutingDecision].
 *
 * The pure decision logic (spec section 5.1) lives in `:shared:domain` as
 * [OnboardingRoutingDecision] so the Android and Linux-desktop products share one
 * implementation (rule 4: no business-logic copies). This wrapper only adapts
 * [SessionStore.Record] — an Android `SharedPreferences`-backed type — into the
 * primitive session facts the shared decision consumes, and maps the shared
 * [OnboardingRoutingDecision.AppMode] back to this module's [AppMode] so existing
 * call sites ([MainActivity]) are unchanged.
 */
object OnboardingRoutingPolicy {

    /** Top-level launch mode (kept for Android call-site compatibility). */
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
    ): AppMode = when (
        OnboardingRoutingDecision.decide(
            recordOrigin = record?.origin,
            recordUsername = record?.username,
            recordKioskMode = record?.kioskMode ?: false,
            profileOrigin = profileOrigin,
            hasMatchingToken = hasMatchingToken,
        )
    ) {
        OnboardingRoutingDecision.AppMode.ONBOARDING -> AppMode.ONBOARDING
        OnboardingRoutingDecision.AppMode.MAIN -> AppMode.MAIN
    }
}
