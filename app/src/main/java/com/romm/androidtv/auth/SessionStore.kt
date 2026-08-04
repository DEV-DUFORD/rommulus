package com.romm.androidtv.auth

import android.content.SharedPreferences
import com.romm.androidtv.network.RommOrigin

/** Stable tag for all auth-loop boundary diagnostics (logcat -s RommAuthDx). */
private const val TAG = "RommAuthDx"

/** Safe diagnostic logger: swallows unmocked android.util.Log in JVM unit tests. */
private fun diagLog(priority: Int, message: String) {
    try { android.util.Log.println(priority, TAG, message) } catch (_: Exception) { /* JVM test env */ }
}

/**
 * Durable record of the last verified RomM session, independent of WebView's
 * cookie store (LIBRETRO_REFACTOR.md section 5, `auth/SessionStore.kt`; Phase 1
 * exit criterion: "without making WebView cookies the only source of truth").
 *
 * This does not itself authenticate anything — actual HTTP session state still
 * lives in the OkHttp cookie jar and Android's [android.webkit.CookieManager].
 * [SessionStore] only durably records *that* a session existed and for which
 * server/user, so later phases (device registration, save provenance) have a
 * persisted fact to build on across process restarts, without depending on a
 * live WebView cookie jar (section 11.4).
 *
 * No secret material (passwords, session tokens, cookies) is ever written here.
 */
class SessionStore(private val prefs: SharedPreferences) {

    data class Record(
        val origin: String,
        val username: String?,
        val verifiedAtEpochMillis: Long,
    )

    /**
     * Durable, synchronous write using [android.content.SharedPreferences.Editor.commit]
     * (which returns success and guarantees the write reaches disk before returning).
     *
     * Returns `true` only when the commit succeeded; a `false` result means the
     * record is NOT durably persisted (callers such as onboarding treat this as
     * terminal [TokenPersistResult.Failure]/persistence failure rather than
     * proceeding with an in-memory-only write).
     *
     * This replaces the previous fire-and-forget `apply()` write so onboarding
     * can verify durability before creating a client token scoped to this session.
     */
    fun save(origin: String, username: String?, verifiedAtEpochMillis: Long = System.currentTimeMillis()): Boolean {
        val committed = prefs.edit()
            .putString(KEY_ORIGIN, origin)
            .putString(KEY_USERNAME, username)
            .putLong(KEY_VERIFIED_AT, verifiedAtEpochMillis)
            .commit()
        val usernamePresent = username != null
        diagLog(android.util.Log.DEBUG, "SessionStore.save: completed usernamePresent=$usernamePresent committed=$committed")
        return committed
    }

    fun current(): Record? {
        val origin = prefs.getString(KEY_ORIGIN, null) ?: run {
            diagLog(android.util.Log.DEBUG, "SessionStore.current: absent")
            return null
        }
        val username = prefs.getString(KEY_USERNAME, null)
        val verifiedAt = prefs.getLong(KEY_VERIFIED_AT, 0L)
        val record = Record(origin, username, verifiedAt)
        diagLog(android.util.Log.DEBUG, "SessionStore.current: present=true")
        return record
    }

    /**
     * Returns the current [Record] only when it is *coherent* with the active
     * profile origin: a non-blank origin, a non-blank username, and an origin
     * that is canonically equivalent (same scheme/host/effective-port/base-path)
     * to [profileOrigin] after normalization. Returns null when any fact is
     * missing or the origins disagree — used to decide whether a persisted
     * session is trustworthy for the currently-configured server.
     */
    fun coherentRecord(profileOrigin: String?): Record? {
        val record = current() ?: run {
            diagLog(android.util.Log.DEBUG, "SessionStore.coherentRecord: absent")
            return null
        }
        if (record.origin.isBlank()) return null
        if (record.username.isNullOrBlank()) return null
        if (profileOrigin.isNullOrBlank()) return null

        val recordOrigin = RommOrigin.parse(record.origin) ?: run {
            diagLog(android.util.Log.DEBUG, "SessionStore.coherentRecord: record origin unparseable")
            return null
        }
        val profileParsed = RommOrigin.parse(profileOrigin) ?: return null
        val sameOrigin = recordOrigin.isSameOrigin(profileParsed) &&
            recordOrigin.path == profileParsed.path
        diagLog(android.util.Log.DEBUG, "SessionStore.coherentRecord: sameOrigin=$sameOrigin")
        return if (sameOrigin) record else null
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_ORIGIN)
            .remove(KEY_USERNAME)
            .remove(KEY_VERIFIED_AT)
            .apply()
        val after = current()
        val keysAbsent = after == null
        diagLog(android.util.Log.DEBUG, "SessionStore.clear: completed keysAbsent=$keysAbsent")
    }

    companion object {
        const val PREFS_NAME = "romm_session"
        private const val KEY_ORIGIN = "last_origin"
        private const val KEY_USERNAME = "last_username"
        private const val KEY_VERIFIED_AT = "last_verified_at"
    }
}
