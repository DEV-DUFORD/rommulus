package com.romm.androidtv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.romm.androidtv.BuildConfig
import com.romm.androidtv.auth.AuthRepository
import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.config.ServerProfile
import com.romm.androidtv.config.SettingsRepository
import com.romm.androidtv.library.ui.RommTheme
import com.romm.androidtv.library.ui.applyTheme
import com.romm.androidtv.model.HeartbeatError as NetworkHeartbeatError
import com.romm.androidtv.network.HeartbeatCallResult
import com.romm.androidtv.network.InvalidReason
import com.romm.androidtv.network.RommOrigin
import com.romm.androidtv.network.RommServerAddress
import com.romm.androidtv.network.ServerAddressResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

/** Full UI state emitted by [SettingsViewModel]. */
data class SettingsUiState(
    /** Text shown in the origin TextField — always preserves exactly what the user typed. */
    val originText: String = "",
    /** Validation error from the last [onOriginTextChanged] or [onSave], null when valid. */
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
    /** App version string from BuildConfig. */
    val appVersion: String = BuildConfig.VERSION_NAME,
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
    val hideUnsupportedSystems: Boolean = false,
    /**
     * Advanced, opt-in setting (LIBRETRO_REFACTOR.md section 10): when true,
     * [com.romm.androidtv.romm.RomRepositoryImpl.stageForLaunch] verifies a
     * ROM's declared SHA-1 hash before launch and rejects a mismatch. Off by
     * default — most users trust their library and don't need this overhead.
     */
    val verifySha1OnLaunch: Boolean = false,
    /**
     * Advanced save-cleanup toggle (on by default): when true, SRAM uploads
     * after a game session ask the server to auto-clean the "autosave" slot to
     * a short recent history (5 files) instead of growing unbounded. When
     * false, every uploaded copy is kept on the server.
     */
    val autocleanSavesOnUpload: Boolean = true,
    /** Currently active theme chosen from the Settings screen. */
    val activeTheme: RommTheme = RommTheme.RomMulus,
)

/**
 * Drives the native Settings screen. Manages origin editing with validation,
 * save/restore-default actions, session invalidation on origin change, and
 * connection-check heartbeat calls.
 *
 * Uses functional interfaces for all dependencies so unit tests can inject mocks
 * without touching config/, auth/, or network/ packages.
 *
 * **Residual cookie behavior** (documented): Android's CookieManager still holds
 * WebView cookies from the old origin until a new login flow overwrites them.
 * We do not manipulate cookies directly — that logic lives in `network/` and
 * `auth/`, outside our ownership boundary. The stale cookies are harmless: they
 * target the old host, so they won't match requests to the new origin. A fresh
 * login against the new origin replaces them via [AuthRepository.syncCookiesToWebView].
 */
class SettingsViewModel(
    private val getCurrentProfile: () -> ServerProfile,
    private val setOriginFn: (String) -> Unit,
    private val clearOverrideFn: () -> Unit,
    private val getSessionRecord: () -> SessionStore.Record?,
    private val clearSessionFn: () -> Unit,
    private val checkHeartbeatFn: suspend (String) -> HeartbeatCallResult,
    private val loginFn: suspend (origin: String, username: String, password: CharArray) -> com.romm.androidtv.network.AuthFlowResult,
    private val onLoginSuccess: () -> Unit,
    private val buildDefaultOrigin: String,
    private val onSessionInvalidated: () -> Unit,
    private val getHideUnsupportedSystems: () -> Boolean = { false },
    private val setHideUnsupportedSystemsFn: (Boolean) -> Unit = {},
    private val getVerifySha1OnLaunch: () -> Boolean = { false },
    private val setVerifySha1OnLaunchFn: (Boolean) -> Unit = {},
    private val getAutocleanSavesOnUpload: () -> Boolean = { true },
    private val setAutocleanSavesOnUploadFn: (Boolean) -> Unit = {},
    private val getTheme: () -> String = { RommTheme.RomMulus.name },
    private val setThemeFn: (String) -> Unit = {},
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadCurrentState()
    }

    /** Loads persisted origin and session info into UI state. */
    private fun loadCurrentState() {
        val profile = getCurrentProfile()
        val session = getSessionRecord()
        _uiState.value = SettingsUiState(
            originText = profile.origin,
            currentOrigin = profile.origin,
            currentUsername = session?.username,
            hideUnsupportedSystems = getHideUnsupportedSystems(),
            verifySha1OnLaunch = getVerifySha1OnLaunch(),
            autocleanSavesOnUpload = getAutocleanSavesOnUpload(),
            activeTheme = RommTheme.fromStorage(getTheme()),
        )
    }

    /** Toggles the opt-in "hide unsupported-system games" library filter and persists it immediately. */
    fun onHideUnsupportedSystemsChanged(hide: Boolean) {
        setHideUnsupportedSystemsFn(hide)
        _uiState.value = _uiState.value.copy(hideUnsupportedSystems = hide)
    }

    /** Toggles the opt-in "verify SHA-1 on launch" advanced setting and persists it immediately. */
    fun onVerifySha1OnLaunchChanged(verify: Boolean) {
        setVerifySha1OnLaunchFn(verify)
        _uiState.value = _uiState.value.copy(verifySha1OnLaunch = verify)
    }

    /** Toggles the advanced "auto-clean uploaded saves" setting and persists it immediately. */
    fun onAutocleanSavesOnUploadChanged(enabled: Boolean) {
        setAutocleanSavesOnUploadFn(enabled)
        _uiState.value = _uiState.value.copy(autocleanSavesOnUpload = enabled)
    }

    /**
     * Applies a newly chosen theme: persists it, updates the global active
     * palette (recomposing the whole app via [applyTheme]) and the local
     * Settings UI state.
     */
    fun onThemeSelected(theme: RommTheme) {
        setThemeFn(theme.name)
        applyTheme(theme)
        _uiState.value = _uiState.value.copy(activeTheme = theme)
    }

    /** Called on every TextField [onValueChange]. Validates and tracks dirty state. */
    fun onOriginTextChanged(newText: String) {
        val state = _uiState.value
        val trimmed = newText.trim()
        val error = validateOrigin(trimmed)
        val changed = trimmed != state.currentOrigin
        _uiState.value = state.copy(
            originText = newText,
            validationError = error,
            saveSuccessMessage = null,
            saveErrorMessage = null,
            connectionCheck = ConnectionCheckState.Idle,
            originChanged = changed,
        )
    }

    /** Persist the edited origin. Clears session if origin actually changed. */
    fun onSave() {
        val state = _uiState.value
        val trimmed = state.originText.trim()

        val error = validateOrigin(trimmed)
        if (error != null) {
            _uiState.value = state.copy(
                validationError = error,
                saveErrorMessage = "Fix the server address before saving",
                saveSuccessMessage = null,
            )
            return
        }

        // Persist immediately (Prefs write is synchronous).
        setOriginFn(trimmed)

        // Compare using RommOrigin semantics: https://host == https://host:443.
        val oldOrigin = state.currentOrigin
        val reallyChanged = originsDiffer(oldOrigin, trimmed)

        if (reallyChanged && getSessionRecord() != null) {
            // Invalidate durable session record and fire navigation callback.
            viewModelScope.launch {
                clearSessionFn()
                _uiState.value = SettingsUiState(
                    originText = trimmed,
                    currentOrigin = trimmed,
                    saveSuccessMessage = "Server updated — session cleared, please log in",
                    originChanged = false,
                )
                onSessionInvalidated()
            }
        } else {
            _uiState.value = state.copy(
                saveSuccessMessage = if (reallyChanged) "Saved" else "No changes",
                saveErrorMessage = null,
                currentOrigin = trimmed,
                originChanged = false,
            )
        }
    }

    /** Remove persisted override, revert to build-time default origin. */
    fun onRestoreDefault() {
        clearOverrideFn()
        val defaultOrigin = buildDefaultOrigin
        val state = _uiState.value
        val reallyChanged = originsDiffer(state.currentOrigin, defaultOrigin)

        if (reallyChanged && getSessionRecord() != null) {
            // Invalidate durable session record and fire navigation callback.
            viewModelScope.launch {
                clearSessionFn()
                _uiState.value = SettingsUiState(
                    originText = defaultOrigin,
                    currentOrigin = defaultOrigin,
                    saveSuccessMessage = "Restored to build default — session cleared, please log in",
                    originChanged = false,
                )
                onSessionInvalidated()
            }
        } else {
            _uiState.value = state.copy(
                originText = defaultOrigin,
                validationError = null,
                saveSuccessMessage = "Restored to build default",
                saveErrorMessage = null,
                connectionCheck = ConnectionCheckState.Idle,
                currentOrigin = defaultOrigin,
                originChanged = reallyChanged,
            )
        }
    }

    /** Run a heartbeat check against the currently edited (or persisted) origin. */
    fun onCheckConnection() {
        val state = _uiState.value
        val trimmed = state.originText.trim()

        val error = validateOrigin(trimmed)
        if (error != null) {
            _uiState.value = state.copy(
                validationError = error,
                connectionCheck = ConnectionCheckState.Error(error),
            )
            return
        }

        _uiState.value = state.copy(connectionCheck = ConnectionCheckState.Loading)

        viewModelScope.launch {
            val result = checkHeartbeatFn(trimmed)
            _uiState.value = state.copy(
                connectionCheck = when (result) {
                    is HeartbeatCallResult.Success ->
                        ConnectionCheckState.Success(result.response.version)
                    is HeartbeatCallResult.Failure ->
                        ConnectionCheckState.Error(formatHeartbeatError(result.error))
                },
            )
        }
    }

    /** Called on every password TextField [onValueChange]. Never persisted; cleared on attempt. */
    fun onPasswordTextChanged(newText: String) {
        _uiState.value = _uiState.value.copy(passwordText = newText, loginState = SettingsLoginState.Idle)
    }

    /** Called on every username TextField [onValueChange]. */
    fun onUsernameTextChanged(newText: String) {
        _uiState.value = _uiState.value.copy(usernameText = newText, loginState = SettingsLoginState.Idle)
    }

    /**
     * Logs in with the username/password entered in this screen, against the currently
     * persisted origin. On success, records the session/durable token exactly like the
     * existing login flow and notifies [onLoginSuccess] so the caller can navigate away
     * (e.g. straight into Native Library) without requiring the WebView login form.
     */
    fun onLogin() {
        val state = _uiState.value
        val username = state.usernameText.trim()
        val password = state.passwordText
        val origin = state.currentOrigin

        if (username.isBlank() || password.isBlank()) {
            _uiState.value = state.copy(
                loginState = SettingsLoginState.Error("Username and password are required"),
            )
            return
        }
        if (validateOrigin(origin) != null) {
            _uiState.value = state.copy(
                loginState = SettingsLoginState.Error("Fix the server address before logging in"),
            )
            return
        }

        _uiState.value = state.copy(loginState = SettingsLoginState.Loading)
        viewModelScope.launch {
            val result = loginFn(origin, username, password.toCharArray())
            when (result) {
                is com.romm.androidtv.network.AuthFlowResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        // Password never lingers in state once the attempt is resolved.
                        passwordText = "",
                        currentUsername = result.verifiedUser.username,
                        loginState = SettingsLoginState.Success,
                    )
                    onLoginSuccess()
                }
                is com.romm.androidtv.network.AuthFlowResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        passwordText = "",
                        loginState = SettingsLoginState.Error(formatAuthError(result.error)),
                    )
                }
            }
        }
    }

    private fun formatAuthError(error: com.romm.androidtv.network.AuthError): String = when (error) {
        com.romm.androidtv.network.AuthError.INVALID_CREDENTIALS -> "Invalid username or password"
        com.romm.androidtv.network.AuthError.SERVER_ERROR -> "Server error during login"
        com.romm.androidtv.network.AuthError.NETWORK_ERROR -> "Network unreachable"
        com.romm.androidtv.network.AuthError.TLS_ERROR -> "TLS / certificate error"
        com.romm.androidtv.network.AuthError.POST_LOGIN_HEARTBEAT_FAILED -> "Server unreachable after login"
        com.romm.androidtv.network.AuthError.VERIFICATION_FAILED -> "Login could not be verified"
        com.romm.androidtv.network.AuthError.ORIGIN_NOT_CONFIGURED -> "Origin not configured"
        com.romm.androidtv.network.AuthError.LOGIN_NOT_AVAILABLE -> "Login is not available on this server"
    }

    // ---- Validation ----

    /** Returns null if [origin] is valid, or a human-readable error string. */
    internal fun validateOrigin(origin: String): String? = when (
        val result = RommServerAddress.parseAndNormalize(origin)
    ) {
        is ServerAddressResult.Valid -> null
        is ServerAddressResult.Invalid -> originErrorMessage(result.reason)
    }

    private fun originErrorMessage(reason: InvalidReason): String = when (reason) {
        InvalidReason.BLANK -> "Server address is required"
        InvalidReason.UNSUPPORTED_SCHEME -> "Only HTTP and HTTPS schemes are supported"
        InvalidReason.MISSING_HOST -> "Host is required"
        InvalidReason.INSECURE_PUBLIC_HTTP ->
            "Public HTTP origins are not allowed; use HTTPS or a private LAN address"
        else -> "Invalid URL format"
    }

    /** Compares two origin strings using [RommOrigin] semantics (normalizes scheme/port). */
    private fun originsDiffer(a: String, b: String): Boolean {
        val pa = RommOrigin.parse(a)
        val pb = RommOrigin.parse(b)
        if (pa == null || pb == null) return a != b
        return !pa.isSameOrigin(pb)
    }

    private fun formatHeartbeatError(error: NetworkHeartbeatError): String = when (error) {
        NetworkHeartbeatError.NETWORK_ERROR -> "Network unreachable"
        NetworkHeartbeatError.TLS_ERROR -> "TLS / certificate error"
        NetworkHeartbeatError.HTTP_ERROR -> "HTTP error from server"
        NetworkHeartbeatError.PARSE_ERROR -> "Invalid response format"
        NetworkHeartbeatError.ORIGIN_NOT_CONFIGURED -> "Origin not configured"
    }

    /** Factory used by MainActivity to construct this ViewModel with existing dependencies. */
    class Factory(
        private val settingsRepository: SettingsRepository,
        private val sessionStore: SessionStore,
        private val authRepository: AuthRepository,
        private val buildDefaultOrigin: String,
        private val onSessionInvalidated: () -> Unit,
        private val onLoginSuccess: () -> Unit = {},
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                getCurrentProfile = { settingsRepository.currentProfile() },
                setOriginFn = { origin -> settingsRepository.setOrigin(origin) },
                clearOverrideFn = { settingsRepository.clearOverride() },
                getSessionRecord = { sessionStore.current() },
                clearSessionFn = {
                    val session = sessionStore.current()
                    session?.let { s ->
                        authRepository.clearClientTokenForCurrentSession(s.origin, s.username ?: "")
                    }
                    sessionStore.clear()
                },
                checkHeartbeatFn = { origin -> authRepository.checkHeartbeat(origin) },
                loginFn = { origin, username, password -> authRepository.login(origin, username, password) },
                onLoginSuccess = onLoginSuccess,
                buildDefaultOrigin = buildDefaultOrigin,
                onSessionInvalidated = onSessionInvalidated,
                getHideUnsupportedSystems = { settingsRepository.hideUnsupportedSystems() },
                setHideUnsupportedSystemsFn = { hide -> settingsRepository.setHideUnsupportedSystems(hide) },
                getVerifySha1OnLaunch = { settingsRepository.verifySha1OnLaunch() },
                setVerifySha1OnLaunchFn = { verify -> settingsRepository.setVerifySha1OnLaunch(verify) },
                getAutocleanSavesOnUpload = { settingsRepository.autocleanSavesOnUpload() },
                setAutocleanSavesOnUploadFn = { enabled -> settingsRepository.setAutocleanSavesOnUpload(enabled) },
                getTheme = { settingsRepository.theme() },
                setThemeFn = { theme -> settingsRepository.setTheme(theme) },
            ) as T
        }
    }
}
