package com.romm.androidtv.auth

/**
 * Platform-neutral seam over durable RomM session-record persistence.
 *
 * Production Android is backed by [com.romm.androidtv.auth.SessionStore] via the
 * app-side `AndroidSessionStorage` adapter; a plain JVM (Linux desktop, tests)
 * supplies its own implementation. Behavior mirrors `SessionStore` exactly so
 * callers ([AuthRepository], [QrLoginRepository]) see no change.
 */
interface SessionStorage {

    /** Durable record of the last verified RomM session. Mirrors `SessionStore.Record`. */
    data class Record(
        val origin: String,
        val username: String?,
        val verifiedAtEpochMillis: Long,
        /** True when this is an anonymous read-only (kiosk/demo) session, not a full login. */
        val kioskMode: Boolean = false,
    )

    /**
     * Durable, synchronous write. Returns `true` only when the record was
     * durably persisted (mirrors `SessionStore.save`'s commit semantics).
     */
    fun save(
        origin: String,
        username: String?,
        verifiedAtEpochMillis: Long = System.currentTimeMillis(),
        kioskMode: Boolean = false,
    ): Boolean

    /**
     * Returns the current [Record] only when it is coherent with [profileOrigin]
     * (non-blank origin/username and canonically-equivalent origin). Mirrors
     * `SessionStore.coherentRecord`.
     */
    fun coherentRecord(profileOrigin: String?): Record?

    /** Clears the persisted session record. */
    fun clear()
}
