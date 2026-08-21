package com.romm.androidtv.auth

import com.romm.androidtv.model.HeartbeatResponse
import com.romm.androidtv.network.VerifiedUser
import com.romm.androidtv.romm.ClientToken

/**
 * Phase 2 first-run onboarding contract shared between [AuthRepository] and the
 * future onboarding ViewModel (section 10 of the spec). These are the ONLY
 * result types the composable layer is allowed to see — low-level OkHttp
 * [okhttp3.OkHttpClient], [AuthError], [Throwable], and [HttpUrl] failures are
 * all mapped to these at the repository boundary.
 *
 * Deliberately placed in the `auth` package (NOT an `onboarding/` package,
 * which is reserved for Phase 3) so [AuthRepository] can produce them and a
 * later ViewModel can consume them without leaking network internals.
 */
sealed interface ServerValidationResult {
    /**
     * The server answered a structurally valid heartbeat at [origin].
     *
     * [kioskMode] is true when an unauthenticated read probe succeeded, meaning the
     * server runs in RomM's anonymous read-only kiosk mode (e.g. the public demo).
     * In kiosk mode no username/password login exists and all reads are anonymous.
     */
    data class Valid(
        val origin: String,
        val heartbeat: HeartbeatResponse,
        val kioskMode: Boolean = false,
    ) : ServerValidationResult

    /** Origin could not be parsed into a usable address (blank/missing scheme/bad port/etc.). */
    data object InvalidAddress : ServerValidationResult

    /** Reached a server, but it is not a RomM server (redirect/3xx, non-2xx, HTML/empty/malformed/unrelated JSON). */
    data object NotRomm : ServerValidationResult

    /** A valid RomM server whose setup wizard has not been completed. */
    data object SetupIncomplete : ServerValidationResult

    /** A valid RomM server that does not allow username/password login. */
    data object UserpassDisabled : ServerValidationResult

    /** Origin was rejected before any network call: public HTTP (never allowed). */
    data object InsecurePublicHttp : ServerValidationResult

    /** Transport/network failure (DNS, connection refused, timeout, disconnect). */
    data object NetworkFailure : ServerValidationResult

    /** TLS handshake/certificate failure. */
    data object TlsFailure : ServerValidationResult
}

sealed interface LoginCompletionResult {
    data class Success(val verifiedUser: VerifiedUser, val durableClientToken: ClientToken) : LoginCompletionResult

    data object InvalidCredentials : LoginCompletionResult
    data object NetworkFailure : LoginCompletionResult
    data object TlsFailure : LoginCompletionResult
    data object ServerFailure : LoginCompletionResult
    data object VerificationFailure : LoginCompletionResult
    data object TokenCreationFailure : LoginCompletionResult
    data object TokenVerificationFailure : LoginCompletionResult
    data object PersistenceFailure : LoginCompletionResult

    /**
     * The server refused to create a new client token specifically because
     * this account already has the maximum number of active device tokens
     * (backend `MAX_TOKENS_PER_USER`). Distinct from [TokenCreationFailure]
     * so the UI can tell the user exactly what to do (remove an old device)
     * instead of implying a local device problem.
     */
    data object TokenLimitReached : LoginCompletionResult
}

/**
 * Typed outcome of a durable client-token write. Encryption/commit failures are
 * surfaced (not swallowed) so onboarding can treat persistence as fatal.
 */
sealed interface TokenPersistResult {
    data object Success : TokenPersistResult
    data object Failure : TokenPersistResult
}

/**
 * Central, shared scope list for the native foreground client token.
 *
 * This is the least-privilege union required by native features (library,
 * save-sync, play-session reporting, firmware/BIOS, collections) — it
 * deliberately contains NO admin scopes (`users.*`, `tasks.run`, `logs.read`).
 * Both [AuthRepository]'s existing save-sync worker token path and the
 * onboarding login path acquire with this exact list, so a single durable token
 * serves both.
 */
object RommClientTokenScopes {
    val FOREGROUND_NATIVE = listOf(
        "me.read",
        "roms.read",
        "roms.user.read",
        "roms.user.write",
        "platforms.read",
        "assets.read",
        "assets.write",
        "devices.read",
        "devices.write",
        "firmware.read",
        "collections.read",
        "collections.write",
    )
}

data class QrLoginSession(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val pollIntervalSeconds: Int,
    val installationId: String,
)

sealed interface QrLoginStartResult {
    data class Ready(val session: QrLoginSession) : QrLoginStartResult
    data object Unsupported : QrLoginStartResult
    data object NetworkFailure : QrLoginStartResult
    data object PersistenceFailure : QrLoginStartResult
}

sealed interface QrLoginPollResult {
    data object Pending : QrLoginPollResult
    data object SlowDown : QrLoginPollResult
    data class Success(val verifiedUser: VerifiedUser) : QrLoginPollResult
    data object Denied : QrLoginPollResult
    data object Expired : QrLoginPollResult
    data object InsufficientScopes : QrLoginPollResult
    data object VerificationFailure : QrLoginPollResult
    data object TokenPersistenceFailure : QrLoginPollResult
    data object TokenVerificationFailure : QrLoginPollResult
    data object DeviceIdentityPersistenceFailure : QrLoginPollResult
    data object SessionPersistenceFailure : QrLoginPollResult
    data object NetworkFailure : QrLoginPollResult
}
