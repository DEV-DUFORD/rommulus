package com.romm.androidtv.storage.ports

/** Well-known settings keys used across the application. */
object SettingsKeys {
    const val ORIGIN = "romm_origin"
    const val THEME = "theme"
    const val HIDE_UNSUPPORTED_SYSTEMS = "hide_unsupported_systems"
    const val VERIFY_SHA1_ON_LAUNCH = "verify_sha1_on_launch"
    const val SCANLINES_ENABLED = "video_scanlines_enabled"
    const val INTEGER_SCALING_ENABLED = "video_integer_scaling_enabled"
    const val AUTOCLEAN_SAVES_ON_UPLOAD = "autoclean_saves_on_upload"
    const val ONSCREEN_GAME_CONTROLS = "on_screen_game_controls"
    const val SEGACD_BIOS_ID = "segacd_bios_id"
    const val SEGACD_BIOS_FILE_NAME = "segacd_bios_file_name"
    const val PSX_BIOS_ID = "psx_bios_id"
    const val PSX_BIOS_FILE_NAME = "psx_bios_file_name"
}

/** Immutable snapshot of current settings values. */
data class SettingsSnapshot(val values: Map<String, String>) {
    fun get(key: String): String? = values[key]

    fun boolean(key: String, default: Boolean): Boolean {
        val raw = values[key] ?: return default
        return raw.equals("true", ignoreCase = true) || raw == "1"
    }
}

/** Persistence-neutral key-value settings store. */
interface SettingsStore {
    /** Return a snapshot of all current settings. */
    fun snapshot(): SettingsSnapshot

    /** Merge [updates] into the store; returns the new snapshot. */
    fun write(updates: Map<String, String>): Result<SettingsSnapshot>

    /** Remove specified keys; returns the new snapshot. */
    fun clear(vararg keys: String): Result<SettingsSnapshot>
}
