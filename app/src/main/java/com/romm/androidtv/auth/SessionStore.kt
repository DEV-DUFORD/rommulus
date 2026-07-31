package com.romm.androidtv.auth

import android.content.SharedPreferences

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

    fun save(origin: String, username: String?, verifiedAtEpochMillis: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_ORIGIN, origin)
            .putString(KEY_USERNAME, username)
            .putLong(KEY_VERIFIED_AT, verifiedAtEpochMillis)
            .apply()
        val usernamePresent = username != null
        diagLog(android.util.Log.DEBUG, "SessionStore.save: completed usernamePresent=$usernamePresent")
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
