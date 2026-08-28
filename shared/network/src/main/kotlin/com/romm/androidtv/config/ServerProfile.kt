package com.romm.androidtv.config

/**
 * A durable, user-configurable RomM server profile.
 *
 * Phase 1 only introduces this as a persisted single-profile record (see
 * [SettingsRepository]); multi-server selection is a later capability
 * (LIBRETRO_REFACTOR.md section 5 lists [SettingsRepository] as the seam that
 * eventually manages more than one profile).
 */
data class ServerProfile(
    /** Scheme+host(+port) origin, e.g. "https://romm.example.com". Empty means unconfigured. */
    val origin: String,
) {
    val isConfigured: Boolean get() = origin.isNotBlank()

    companion object {
        val UNCONFIGURED = ServerProfile(origin = "")
    }
}
