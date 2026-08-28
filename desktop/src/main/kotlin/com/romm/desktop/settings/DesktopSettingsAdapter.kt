package com.romm.desktop.settings

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.romm.androidtv.auth.SessionStorage
import com.romm.androidtv.config.ServerProfile
import com.romm.androidtv.library.RommTheme
import com.romm.androidtv.network.RommServerAddress
import com.romm.androidtv.network.ServerAddressResult
import com.romm.androidtv.storage.ports.SettingsKeys
import com.romm.androidtv.storage.ports.SettingsStore

/**
 * Adapts the desktop [SettingsStore] (JSON) + [SessionStorage] (SQLite) pair into the
 * functional surfaces the shared presenters need, mirroring Android's
 * [com.romm.androidtv.config.SettingsRepository] behavior for the desktop product.
 *
 * - [currentProfile] reads `SettingsKeys.ORIGIN` from the JSON store, falling back to
 *   [buildDefaultOrigin] when nothing was ever persisted (mirrors `SettingsRepository`'s
 *   default-origin fallback).
 * - [persistValidatedOrigin] normalizes through [RommServerAddress.parseAndNormalize],
 *   durably writes the canonical origin, and verifies it reads back exactly — returns
 *   `true` only on a valid + durable + round-tripped write.
 * - Boolean/string toggles read/write the JSON store through the shared [SettingsKeys].
 * - Session facts delegate to the injected [SessionStorage].
 */
class DesktopSettingsAdapter(
    private val store: SettingsStore,
    private val sessionStorage: SessionStorage,
    private val buildDefaultOrigin: String,
) {

    fun currentProfile(): ServerProfile {
        val stored = store.snapshot().get(SettingsKeys.ORIGIN)
        return ServerProfile(origin = stored ?: buildDefaultOrigin)
    }

    fun setOrigin(origin: String) {
        store.write(mapOf(SettingsKeys.ORIGIN to origin))
    }

    suspend fun persistValidatedOrigin(origin: String): Boolean {
        val canonical = when (val result = RommServerAddress.parseAndNormalize(origin)) {
            is ServerAddressResult.Invalid -> return false
            is ServerAddressResult.Valid -> result.origin
        }
        val committed = store.write(mapOf(SettingsKeys.ORIGIN to canonical)).isSuccess
        if (!committed) return false
        return store.snapshot().get(SettingsKeys.ORIGIN) == canonical
    }

    fun clearOverride() {
        store.clear(SettingsKeys.ORIGIN)
    }

    fun hideUnsupportedSystems(): Boolean =
        store.snapshot().boolean(SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS, default = true)

    fun setHideUnsupportedSystems(hide: Boolean) {
        store.write(mapOf(SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS to hide.toString()))
    }

    fun verifySha1OnLaunch(): Boolean =
        store.snapshot().boolean(SettingsKeys.VERIFY_SHA1_ON_LAUNCH, default = false)

    fun setVerifySha1OnLaunch(verify: Boolean) {
        store.write(mapOf(SettingsKeys.VERIFY_SHA1_ON_LAUNCH to verify.toString()))
    }

    fun autocleanSavesOnUpload(): Boolean =
        store.snapshot().boolean(SettingsKeys.AUTOCLEAN_SAVES_ON_UPLOAD, default = true)

    fun setAutocleanSavesOnUpload(enabled: Boolean) {
        store.write(mapOf(SettingsKeys.AUTOCLEAN_SAVES_ON_UPLOAD to enabled.toString()))
    }

    fun onScreenGameControlsEnabled(): Boolean =
        store.snapshot().boolean(SettingsKeys.ONSCREEN_GAME_CONTROLS, default = true)

    fun setOnScreenGameControlsEnabled(enabled: Boolean) {
        store.write(mapOf(SettingsKeys.ONSCREEN_GAME_CONTROLS to enabled.toString()))
    }

    // Video Options toggles (mirrors Android's SettingsRepository keys): read at
    // player launch and passed in the v1 request's video block so the player
    // applies the persisted state from the start. All default off, matching
    // both the wire schema defaults and Android's fresh-install behavior.
    fun scanlinesEnabled(): Boolean =
        store.snapshot().boolean(SettingsKeys.SCANLINES_ENABLED, default = false)

    fun integerScalingEnabled(): Boolean =
        store.snapshot().boolean(SettingsKeys.INTEGER_SCALING_ENABLED, default = false)

    fun sharpFilterEnabled(): Boolean =
        store.snapshot().boolean(SettingsKeys.SHARP_FILTER_ENABLED, default = false)

    fun setVideoOptions(scanlines: Boolean, integerScaling: Boolean, sharpFilter: Boolean) {
        store.write(
            mapOf(
                SettingsKeys.SCANLINES_ENABLED to scanlines.toString(),
                SettingsKeys.INTEGER_SCALING_ENABLED to integerScaling.toString(),
                SettingsKeys.SHARP_FILTER_ENABLED to sharpFilter.toString(),
            ),
        )
    }

    fun theme(): String = store.snapshot().get(SettingsKeys.THEME) ?: DEFAULT_THEME

    private val _currentTheme = mutableStateOf(RommTheme.fromStorage(theme()))

    /**
     * The active [RommTheme] as observable Compose state, so the shell (which owns the
     * app-wide `RommulusTheme` wrapper after the Phase 6 integration wave) re-themes live
     * when the user picks a new theme in Settings. Updated by [setTheme]; initialized from
     * the persisted store id.
     */
    val currentTheme: State<RommTheme> get() = _currentTheme

    fun setTheme(theme: String) {
        store.write(mapOf(SettingsKeys.THEME to theme))
        _currentTheme.value = RommTheme.fromStorage(theme)
    }

    fun sessionRecord(): SessionStorage.Record? =
        sessionStorage.coherentRecord(currentProfile().origin)

    fun clearSession() {
        sessionStorage.clear()
    }

    private companion object {
        const val DEFAULT_THEME = "RomMulus"
    }
}
