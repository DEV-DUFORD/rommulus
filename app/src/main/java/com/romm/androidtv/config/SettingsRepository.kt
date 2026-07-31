package com.romm.androidtv.config

import android.content.SharedPreferences

/**
 * Persists the RomM [ServerProfile] across process restarts.
 *
 * This does not replace [com.romm.androidtv.BuildConfig.ROMM_ORIGIN] as the
 * compiled-in default; it lets a future settings UI override the origin at
 * runtime while keeping the existing debug/release build default behavior
 * when nothing has ever been persisted (LIBRETRO_REFACTOR.md section 5,
 * `config/SettingsRepository.kt`).
 */
class SettingsRepository(
    private val prefs: SharedPreferences,
    private val defaultOrigin: String,
) {

    /** Returns the persisted profile, or one backed by [defaultOrigin] if nothing was ever saved. */
    fun currentProfile(): ServerProfile {
        val stored = prefs.getString(KEY_ORIGIN, null)
        return ServerProfile(origin = stored ?: defaultOrigin)
    }

    /** Persists a new origin. Pass a blank string to explicitly mark the profile unconfigured. */
    fun setOrigin(origin: String) {
        prefs.edit().putString(KEY_ORIGIN, origin).apply()
    }

    /** Removes any persisted override, reverting [currentProfile] to [defaultOrigin]. */
    fun clearOverride() {
        prefs.edit().remove(KEY_ORIGIN).apply()
    }

    /**
     * Opt-in library filter (off by default; LIBRETRO_REFACTOR.md section 13,
     * Phase 6): when true, the library grid/shelves hide games on platforms
     * with no approved native core instead of showing a disabled "not
     * supported yet" state for each one.
     */
    fun hideUnsupportedSystems(): Boolean = prefs.getBoolean(KEY_HIDE_UNSUPPORTED, false)

    fun setHideUnsupportedSystems(hide: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_UNSUPPORTED, hide).apply()
    }

    companion object {
        const val PREFS_NAME = "romm_settings"
        private const val KEY_ORIGIN = "romm_origin"
        private const val KEY_HIDE_UNSUPPORTED = "hide_unsupported_systems"
    }
}
