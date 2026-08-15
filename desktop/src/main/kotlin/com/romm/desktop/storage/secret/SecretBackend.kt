package com.romm.desktop.storage.secret

/**
 * Minimal transport seam for a platform keyring (freedesktop Secret Service on
 * Linux; a test double on macOS/CI). The production D-Bus implementation of this
 * seam is added in a later step of the Phase 5 spike.
 *
 * All methods are synchronous and MUST NOT throw. Implementations translate
 * transport failures into the null/Boolean/KeyringState results below so callers
 * can fail closed without an exception boundary.
 */
sealed interface KeyringState {
    /** The service is reachable and the target collection is unlocked. */
    data object Available : KeyringState

    /** The service is reachable but the collection holding app secrets is locked. */
    data object Locked : KeyringState

    /** No Secret Service provider is present (or the D-Bus name has no owner). */
    data object Unavailable : KeyringState

    /** The service or sandbox refused access (e.g. Flatpak denied the name). */
    data class Denied(val reason: String) : KeyringState
}

interface SecretBackend {
    /** Current availability/lock state; used for the actionable "unlock keyring" error. */
    fun state(): KeyringState

    /** Persist [secret] under [scope]. True only if durably committed and immediately re-readable. */
    fun store(scope: String, secret: String): Boolean

    /** Returns the secret for [scope], or null if absent or the keyring is locked/unavailable. */
    fun retrieve(scope: String): String?

    /** Removes the secret for [scope]. Best-effort; no result. */
    fun delete(scope: String)

    /** Removes every secret this application owns. Best-effort; no result. */
    fun deleteAll()
}
