package com.romm.androidtv.config

import android.content.SharedPreferences
import com.romm.androidtv.network.RommServerAddress
import com.romm.androidtv.network.ServerAddressResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /**
     * Durable validated-origin write used by onboarding. Normalizes [origin]
     * through [RommServerAddress.parseAndNormalize] (so public HTTP and other
     * invalid forms are rejected), commits synchronously via `commit()`, and
     * verifies the canonical value reads back exactly before returning.
     *
     * Returns `true` only when the origin was valid AND durably persisted AND
     * read back equal to the canonical value.
     */
    suspend fun persistValidatedOrigin(origin: String): Boolean {
        val canonical = when (val result = RommServerAddress.parseAndNormalize(origin)) {
            is ServerAddressResult.Invalid -> return false
            is ServerAddressResult.Valid -> result.origin
        }
        val committed = prefs.edit().putString(KEY_ORIGIN, canonical).commit()
        if (!committed) return false
        return prefs.getString(KEY_ORIGIN, null) == canonical
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

    /**
     * Reactive source-of-truth for the hide-unsupported-systems preference.
     * Emits the current value on subscription and updates whenever
     * [setHideUnsupportedSystems] is called. Library ViewModels collect this
     * flow to refresh their data immediately when the user toggles the setting
     * from the Settings screen, without requiring navigation or app restart.
     */
    private val _hideUnsupportedSystemsFlow = MutableStateFlow(hideUnsupportedSystems())
    val hideUnsupportedSystemsFlow: StateFlow<Boolean> = _hideUnsupportedSystemsFlow.asStateFlow()

    fun setHideUnsupportedSystems(hide: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_UNSUPPORTED, hide).apply()
        _hideUnsupportedSystemsFlow.value = hide
    }

    /**
     * Advanced, opt-in integrity check (off by default): when true,
     * [com.romm.androidtv.romm.RomRepositoryImpl.stageForLaunch] verifies a
     * ROM's declared `sha1_hash` (pre-download for a raw file, post-extraction
     * for an archive) before letting it launch, and rejects a mismatch as
     * [com.romm.androidtv.romm.StagingOutcome.CorruptedDownload]. This exists
     * to catch real content corruption (e.g. a bad server-side re-scan/repack)
     * but is unnecessary overhead for most users who already trust their
     * library — hence off by default rather than mandatory.
     */
    fun verifySha1OnLaunch(): Boolean = prefs.getBoolean(KEY_VERIFY_SHA1, false)

    fun setVerifySha1OnLaunch(verify: Boolean) {
        prefs.edit().putBoolean(KEY_VERIFY_SHA1, verify).apply()
    }

    /**
     * CRT scanlines overlay (off by default): when true, a scanlines shader is
     * applied over the emulator surface to simulate the look of a retro CRT
     * display. Useful for users who prefer the classic arcade aesthetic.
     */
    fun scanlinesEnabled(): Boolean =
        prefs.getBoolean(KEY_SCANLINES_ENABLED, false)

    /**
     * Persists the scanlines overlay toggle. Returns true only when the
     * underlying SharedPreferences committed the write synchronously.
     */
    fun setScanlinesEnabled(enabled: Boolean): Boolean =
        prefs.edit()
            .putBoolean(KEY_SCANLINES_ENABLED, enabled)
            .commit()

    /**
     * Advanced save-cleanup toggle (on by default): when true, SRAM uploads ask
     * the server to auto-clean the "autosave" slot down to a short recent
     * history (5 files) right after each successful upload. When false, the
     * server keeps every uploaded copy instead of pruning older ones.
     */
    fun autocleanSavesOnUpload(): Boolean = prefs.getBoolean(KEY_AUTOCLEAN_SAVES, true)

    fun setAutocleanSavesOnUpload(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOCLEAN_SAVES, enabled).apply()
    }

    /**
     * Currently active UI theme, stored by id string ("RomMulus"/"RomM").
     * Defaults to the local-appearance "RomMulus" (logo teal) theme.
     */
    fun theme(): String = prefs.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME

    /**
     * Reactive source-of-truth for the active theme. Emits the current value
     * on subscription and updates whenever [setTheme] is called, so the app
     * root can swap the palette live without a restart.
     */
    private val _themeFlow = MutableStateFlow(theme())
    val themeFlow: StateFlow<String> = _themeFlow.asStateFlow()

    fun setTheme(theme: String) {
        prefs.edit().putString(KEY_THEME, theme).apply()
        _themeFlow.value = theme
    }

    data class BiosSelection(
        val firmwareId: Long,
        val fileName: String,
    )

    fun segaCdBiosSelection(): BiosSelection? {
        val firmwareId = prefs.getLong(KEY_SEGACD_BIOS_ID, -1L)
        val fileName = prefs.getString(KEY_SEGACD_BIOS_FILE_NAME, null)
        return if (firmwareId > 0 && !fileName.isNullOrBlank()) {
            BiosSelection(firmwareId, fileName)
        } else {
            null
        }
    }

    fun setSegaCdBiosSelection(firmwareId: Long, fileName: String) {
        require(firmwareId > 0) { "firmwareId must be positive" }
        require(fileName.isNotBlank()) { "fileName must not be blank" }
        prefs.edit()
            .putLong(KEY_SEGACD_BIOS_ID, firmwareId)
            .putString(KEY_SEGACD_BIOS_FILE_NAME, fileName)
            .apply()
    }

    fun psxBiosSelection(): BiosSelection? {
        val firmwareId = prefs.getLong(KEY_PSX_BIOS_ID, -1L)
        val fileName = prefs.getString(KEY_PSX_BIOS_FILE_NAME, null)
        return if (firmwareId > 0 && !fileName.isNullOrBlank()) {
            BiosSelection(firmwareId, fileName)
        } else {
            null
        }
    }

    fun setPsxBiosSelection(firmwareId: Long, fileName: String) {
        require(firmwareId > 0) { "firmwareId must be positive" }
        require(fileName.isNotBlank()) { "fileName must not be blank" }
        prefs.edit()
            .putLong(KEY_PSX_BIOS_ID, firmwareId)
            .putString(KEY_PSX_BIOS_FILE_NAME, fileName)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "romm_settings"
        private const val DEFAULT_THEME = "RomMulus"
        private const val KEY_THEME = "theme"
        private const val KEY_ORIGIN = "romm_origin"
        private const val KEY_HIDE_UNSUPPORTED = "hide_unsupported_systems"
        private const val KEY_VERIFY_SHA1 = "verify_sha1_on_launch"
        private const val KEY_AUTOCLEAN_SAVES = "autoclean_saves_on_upload"
        private const val KEY_SCANLINES_ENABLED = "video_scanlines_enabled"
        private const val KEY_SEGACD_BIOS_ID = "segacd_bios_id"
        private const val KEY_SEGACD_BIOS_FILE_NAME = "segacd_bios_file_name"
        private const val KEY_PSX_BIOS_ID = "psx_bios_id"
        private const val KEY_PSX_BIOS_FILE_NAME = "psx_bios_file_name"
    }
}
