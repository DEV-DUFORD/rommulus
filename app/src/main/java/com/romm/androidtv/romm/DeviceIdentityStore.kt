package com.romm.androidtv.romm

import android.content.SharedPreferences
import java.util.UUID

/**
 * Durable, server- and user-scoped store for the local installation UUID and
 * the RomM-assigned device identity returned by `POST /api/devices`
 * (LIBRETRO_REFACTOR.md section 11.2).
 *
 * Mirrors [com.romm.androidtv.auth.SessionStore]'s pattern: a thin wrapper
 * over [SharedPreferences], no secret material stored. Keys are scoped by a
 * sanitized `origin|username` pair so multiple server/user combinations on
 * the same device never collide, and switching accounts/servers naturally
 * lands on a fresh (or independently-cached) identity.
 */
class DeviceIdentityStore(private val prefs: SharedPreferences) {

    /**
     * Returns the stable local installation UUID for this server + user scope,
     * generating and persisting a new one on first use. This is the value sent
     * as `client_device_identifier` so the server can dedupe re-registrations
     * from the same physical install.
     */
    fun installationId(origin: String, username: String): String {
        val key = installationKey(origin, username)
        val existing = prefs.getString(key, null)
        if (existing != null) return existing

        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(key, generated).apply()
        return generated
    }

    /** Returns the cached RomM device identity for this scope, if one was previously registered. */
    fun cachedDeviceId(origin: String, username: String): String? =
        prefs.getString(deviceIdKey(origin, username), null)

    /** Persists a newly-registered (or reused) RomM device identity for this scope. */
    fun saveDeviceId(origin: String, username: String, rommDeviceId: String) {
        prefs.edit().putString(deviceIdKey(origin, username), rommDeviceId).apply()
    }

    /**
     * Clears the cached RomM device identity, but keeps the local installation
     * UUID so a future re-registration can still be recognized as the same
     * install by the server. Call on explicit sign-out/data reset, or when the
     * server rejects the cached identity and requires re-registration.
     */
    fun forgetDeviceId(origin: String, username: String) {
        prefs.edit().remove(deviceIdKey(origin, username)).apply()
    }

    private fun installationKey(origin: String, username: String) = "install_id:${scopeKey(origin, username)}"
    private fun deviceIdKey(origin: String, username: String) = "device_id:${scopeKey(origin, username)}"

    private fun scopeKey(origin: String, username: String) =
        "${sanitize(origin)}|${sanitize(username)}"

    private fun sanitize(raw: String) = raw.trim().lowercase()

    companion object {
        const val PREFS_NAME = "romm_device_identity"
    }
}
