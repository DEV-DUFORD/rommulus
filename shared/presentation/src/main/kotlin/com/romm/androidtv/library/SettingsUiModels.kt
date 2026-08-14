package com.romm.androidtv.library

/** Connection-check result states for the Settings screen. */
sealed interface ConnectionCheckState {
    object Idle : ConnectionCheckState
    object Loading : ConnectionCheckState
    data class Success(val version: String?) : ConnectionCheckState
    data class Error(val message: String) : ConnectionCheckState
}

/** Login-action result states for the Settings screen credentials form. */
sealed interface SettingsLoginState {
    object Idle : SettingsLoginState
    object Loading : SettingsLoginState
    object Success : SettingsLoginState
    data class Error(val message: String) : SettingsLoginState
}

/** Full UI state emitted by [SettingsPresenter]. */
data class SettingsUiState(
    /** Text shown in the origin TextField — always preserves exactly what the user typed. */
    val originText: String = "",
    /** Validation error from the last [SettingsPresenter.onOriginTextChanged] or [SettingsPresenter.onSave], null when valid. */
    val validationError: String? = null,
    /** Inline success message after a save, cleared on next edit. */
    val saveSuccessMessage: String? = null,
    /** Inline error message after a failed save, cleared on next edit. */
    val saveErrorMessage: String? = null,
    /** State of the Check Connection action. */
    val connectionCheck: ConnectionCheckState = ConnectionCheckState.Idle,
    /** Username from the currently recorded session (read-only info). Null when not logged in. */
    val currentUsername: String? = null,
    /** The origin currently persisted in SettingsRepository (the "source of truth"). */
    val currentOrigin: String = "",
    /** App version string, injected by the platform layer (BuildConfig on Android). */
    val appVersion: String = "",
    /** True when the edited originText differs from persisted currentOrigin. */
    val originChanged: Boolean = false,
    /** Text in the username field of the credentials form. Never persisted directly. */
    val usernameText: String = "",
    /** Text in the password field of the credentials form. Cleared after every login attempt. */
    val passwordText: String = "",
    /** State of the in-progress/last login attempt from this screen. */
    val loginState: SettingsLoginState = SettingsLoginState.Idle,
    /**
     * Opt-in library filter (LIBRETRO_REFACTOR.md section 13, Phase 6): when
     * true, the library grid/shelves hide games on platforms with no
     * approved native core. Off by default.
     */
    val hideUnsupportedSystems: Boolean = true,
    /**
     * Advanced, opt-in setting (LIBRETRO_REFACTOR.md section 10): when true,
     * the ROM repository verifies a ROM's declared SHA-1 hash before launch
     * and rejects a mismatch. Off by default — most users trust their library
     * and don't need this overhead.
     */
    val verifySha1OnLaunch: Boolean = false,
    /**
     * Advanced save-cleanup toggle (on by default): when true, SRAM uploads
     * after a game session ask the server to auto-clean the "autosave" slot to
     * a short recent history (5 files) instead of growing unbounded. When
     * false, every uploaded copy is kept on the server.
     */
    val autocleanSavesOnUpload: Boolean = true,
    /**
     * On-screen game controls toggle (on by default): when true, touch controls
     * are rendered over the emulator surface during gameplay. Disabled on TV
     * devices that lack a touchscreen.
     */
    val onScreenGameControlsEnabled: Boolean = true,
    /** Currently active theme chosen from the Settings screen. */
    val activeTheme: RommTheme = RommTheme.RomMulus,
)
