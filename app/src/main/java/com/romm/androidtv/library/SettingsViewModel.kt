package com.romm.androidtv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.romm.androidtv.BuildConfig
import com.romm.androidtv.auth.AuthRepository
import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.config.ServerProfile
import com.romm.androidtv.config.SettingsRepository
import com.romm.androidtv.model.HeartbeatError as NetworkHeartbeatError
import com.romm.androidtv.network.HeartbeatCallResult
import com.romm.androidtv.network.RommOrigin
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
    private val buildDefaultOrigin: String,
    private val onSessionInvalidated: () -> Unit,
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
        )
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

    // ---- Validation ----

    /** Returns null if [origin] is valid, or a human-readable error string. */
    internal fun validateOrigin(origin: String): String? {
        if (origin.isBlank()) return "Server address is required"
        val parsed = RommOrigin.parse(origin)
        if (parsed == null) return "Invalid URL format"
        if (parsed.scheme !in listOf("http", "https")) {
            return "Only HTTP and HTTPS schemes are supported"
        }
        if (parsed.host.isBlank()) return "Host is required"
        return null
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
                buildDefaultOrigin = buildDefaultOrigin,
                onSessionInvalidated = onSessionInvalidated,
            ) as T
        }
    }
}
