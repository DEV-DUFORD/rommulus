package com.romm.androidtv.library

import com.romm.androidtv.auth.SessionStorage
import com.romm.androidtv.config.ServerProfile
import com.romm.androidtv.model.HeartbeatError as NetworkHeartbeatError
import com.romm.androidtv.network.AuthError
import com.romm.androidtv.network.AuthFlowResult
import com.romm.androidtv.network.HeartbeatCallResult
import com.romm.androidtv.network.InvalidReason
import com.romm.androidtv.network.RommOrigin
import com.romm.androidtv.network.RommServerAddress
import com.romm.androidtv.network.ServerAddressResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 * login against the new origin replaces them via [com.romm.androidtv.auth.AuthRepository].
 *
 * Platform-neutral: all async work runs in the injected [scope] so the whole
 * presenter is exercisable by plain JVM unit tests (Linux port Phase 4). The
 * platform layer injects [appVersion] (BuildConfig on Android) and the
 * [applyTheme] hook that pushes the chosen theme into the running UI.
 */
class SettingsPresenter(
    private val scope: CoroutineScope,
    private val getCurrentProfile: () -> ServerProfile,
    private val setOriginFn: (String) -> Unit,
    private val clearOverrideFn: () -> Unit,
    private val getSessionRecord: () -> SessionStorage.Record?,
    private val clearSessionFn: () -> Unit,
    private val checkHeartbeatFn: suspend (String) -> HeartbeatCallResult,
    private val loginFn: suspend (origin: String, username: String, password: CharArray) -> AuthFlowResult,
    private val onLoginSuccess: () -> Unit,
    private val onSessionInvalidated: () -> Unit,
    private val getHideUnsupportedSystems: () -> Boolean = { false },
    private val setHideUnsupportedSystemsFn: (Boolean) -> Unit = {},
    private val getVerifySha1OnLaunch: () -> Boolean = { false },
    private val setVerifySha1OnLaunchFn: (Boolean) -> Unit = {},
    private val getAutocleanSavesOnUpload: () -> Boolean = { true },
    private val setAutocleanSavesOnUploadFn: (Boolean) -> Unit = {},
    private val getOnScreenGameControlsEnabled: () -> Boolean = { true },
    private val setOnScreenGameControlsEnabledFn: (Boolean) -> Unit = {},
    private val onScreenGameControlsFlow: Flow<Boolean>? = null,
    private val getTouchControlHapticsEnabled: () -> Boolean = { false },
    private val setTouchControlHapticsEnabledFn: (Boolean) -> Unit = {},
    private val touchControlHapticsFlow: Flow<Boolean>? = null,
    private val getTheme: () -> String = { RommTheme.RomMulus.name },
    private val setThemeFn: (String) -> Unit = {},
    /** Platform hook that applies the chosen theme to the running UI. */
    private val applyTheme: (RommTheme) -> Unit = {},
    private val appVersion: String,
    private val buildDefaultOrigin: String,
) {

    private val _uiState = MutableStateFlow(SettingsUiState(appVersion = appVersion))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadCurrentState()
        onScreenGameControlsFlow?.let { flow ->
            scope.launch {
                flow.collect { enabled ->
                    _uiState.value = _uiState.value.copy(onScreenGameControlsEnabled = enabled)
                }
            }
        }
        touchControlHapticsFlow?.let { flow ->
            scope.launch {
                flow.collect { enabled ->
                    _uiState.value = _uiState.value.copy(touchControlHapticsEnabled = enabled)
                }
            }
        }
    }

    /** Loads persisted origin and session info into UI state. */
    private fun loadCurrentState() {
        val profile = getCurrentProfile()
        val session = getSessionRecord()
        _uiState.value = SettingsUiState(
            appVersion = appVersion,
            originText = profile.origin,
            currentOrigin = profile.origin,
            currentUsername = session?.username,
            hideUnsupportedSystems = getHideUnsupportedSystems(),
            verifySha1OnLaunch = getVerifySha1OnLaunch(),
            autocleanSavesOnUpload = getAutocleanSavesOnUpload(),
            onScreenGameControlsEnabled = getOnScreenGameControlsEnabled(),
            touchControlHapticsEnabled = getTouchControlHapticsEnabled(),
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

    /** Toggles the on-screen game controls setting and persists it immediately. */
    fun onOnScreenGameControlsChanged(enabled: Boolean) {
        setOnScreenGameControlsEnabledFn(enabled)
        _uiState.value = _uiState.value.copy(onScreenGameControlsEnabled = enabled)
    }

    /** Toggles touch-control haptics and persists it immediately. */
    fun onTouchControlHapticsChanged(enabled: Boolean) {
        setTouchControlHapticsEnabledFn(enabled)
        _uiState.value = _uiState.value.copy(touchControlHapticsEnabled = enabled)
    }

    /**
     * Applies a newly chosen theme: persists it, hands the theme to the
     * platform [applyTheme] hook (which recomposes the whole app on Android),
     * and updates the local Settings UI state.
     */
    fun onThemeSelected(theme: RommTheme) {
        setThemeFn(theme.name)
        applyTheme(theme)
        _uiState.value = _uiState.value.copy(activeTheme = theme)
    }

    /** Called on every TextField value change. Validates and tracks dirty state. */
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
            scope.launch {
                clearSessionFn()
                _uiState.value = SettingsUiState(
                    appVersion = appVersion,
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
            scope.launch {
                clearSessionFn()
                _uiState.value = SettingsUiState(
                    appVersion = appVersion,
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

        scope.launch {
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

    /** Called on every password field value change. Never persisted; cleared on attempt. */
    fun onPasswordTextChanged(newText: String) {
        _uiState.value = _uiState.value.copy(passwordText = newText, loginState = SettingsLoginState.Idle)
    }

    /** Called on every username field value change. */
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
        scope.launch {
            val result = loginFn(origin, username, password.toCharArray())
            when (result) {
                is AuthFlowResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        // Password never lingers in state once the attempt is resolved.
                        passwordText = "",
                        currentUsername = result.verifiedUser.username,
                        loginState = SettingsLoginState.Success,
                    )
                    onLoginSuccess()
                }
                is AuthFlowResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        passwordText = "",
                        loginState = SettingsLoginState.Error(formatAuthError(result.error)),
                    )
                }
            }
        }
    }

    private fun formatAuthError(error: AuthError): String = when (error) {
        AuthError.INVALID_CREDENTIALS -> "Invalid username or password"
        AuthError.SERVER_ERROR -> "Server error during login"
        AuthError.NETWORK_ERROR -> "Network unreachable"
        AuthError.TLS_ERROR -> "TLS / certificate error"
        AuthError.POST_LOGIN_HEARTBEAT_FAILED -> "Server unreachable after login"
        AuthError.VERIFICATION_FAILED -> "Login could not be verified"
        AuthError.ORIGIN_NOT_CONFIGURED -> "Origin not configured"
        AuthError.LOGIN_NOT_AVAILABLE -> "Login is not available on this server"
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
}
