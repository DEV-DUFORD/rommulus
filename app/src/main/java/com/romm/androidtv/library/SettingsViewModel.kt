package com.romm.androidtv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.romm.androidtv.BuildConfig
import com.romm.androidtv.auth.AuthRepository
import com.romm.androidtv.auth.SessionStorage
import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.config.ServerProfile
import com.romm.androidtv.config.SettingsRepository
import com.romm.androidtv.library.ui.applyTheme
import com.romm.androidtv.network.AuthFlowResult
import com.romm.androidtv.network.HeartbeatCallResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin lifecycle wrapper around the platform-neutral [SettingsPresenter]
 * (Linux port Phase 4). All state-machine behavior lives in
 * `:shared:presentation`; this class only binds it to the lifecycle owner's
 * scope, adapts the Android [SessionStore] record to the shared
 * [SessionStorage.Record] seam, supplies the BuildConfig values, and forwards
 * the public API so existing call sites (factory, MainActivity, SettingsScreen)
 * compile unchanged.
 */
class SettingsViewModel(
    private val getCurrentProfile: () -> ServerProfile,
    private val setOriginFn: (String) -> Unit,
    private val clearOverrideFn: () -> Unit,
    private val getSessionRecord: () -> SessionStore.Record?,
    private val clearSessionFn: () -> Unit,
    private val checkHeartbeatFn: suspend (String) -> HeartbeatCallResult,
    private val loginFn: suspend (origin: String, username: String, password: CharArray) -> AuthFlowResult,
    private val onLoginSuccess: () -> Unit,
    private val buildDefaultOrigin: String,
    private val onSessionInvalidated: () -> Unit,
    private val getHideUnsupportedSystems: () -> Boolean = { false },
    private val setHideUnsupportedSystemsFn: (Boolean) -> Unit = {},
    private val getVerifySha1OnLaunch: () -> Boolean = { false },
    private val setVerifySha1OnLaunchFn: (Boolean) -> Unit = {},
    private val getAutocleanSavesOnUpload: () -> Boolean = { true },
    private val setAutocleanSavesOnUploadFn: (Boolean) -> Unit = {},
    private val getOnScreenGameControlsEnabled: () -> Boolean = { true },
    private val setOnScreenGameControlsEnabledFn: (Boolean) -> Unit = {},
    private val getTheme: () -> String = { RommTheme.RomMulus.name },
    private val setThemeFn: (String) -> Unit = {},
) : ViewModel() {

    private val presenter = SettingsPresenter(
        scope = viewModelScope,
        getCurrentProfile = getCurrentProfile,
        setOriginFn = setOriginFn,
        clearOverrideFn = clearOverrideFn,
        getSessionRecord = {
            getSessionRecord()?.let {
                SessionStorage.Record(
                    origin = it.origin,
                    username = it.username,
                    verifiedAtEpochMillis = it.verifiedAtEpochMillis,
                    kioskMode = it.kioskMode,
                )
            }
        },
        clearSessionFn = clearSessionFn,
        checkHeartbeatFn = checkHeartbeatFn,
        loginFn = loginFn,
        onLoginSuccess = onLoginSuccess,
        onSessionInvalidated = onSessionInvalidated,
        getHideUnsupportedSystems = getHideUnsupportedSystems,
        setHideUnsupportedSystemsFn = setHideUnsupportedSystemsFn,
        getVerifySha1OnLaunch = getVerifySha1OnLaunch,
        setVerifySha1OnLaunchFn = setVerifySha1OnLaunchFn,
        getAutocleanSavesOnUpload = getAutocleanSavesOnUpload,
        setAutocleanSavesOnUploadFn = setAutocleanSavesOnUploadFn,
        getOnScreenGameControlsEnabled = getOnScreenGameControlsEnabled,
        setOnScreenGameControlsEnabledFn = setOnScreenGameControlsEnabledFn,
        getTheme = getTheme,
        setThemeFn = setThemeFn,
        applyTheme = { theme -> applyTheme(theme) },
        appVersion = BuildConfig.VERSION_NAME,
        buildDefaultOrigin = buildDefaultOrigin,
    )

    val uiState: StateFlow<SettingsUiState> = presenter.uiState

    fun onSave() {
        presenter.onSave()
    }

    fun onRestoreDefault() {
        presenter.onRestoreDefault()
    }

    fun onCheckConnection() {
        presenter.onCheckConnection()
    }

    fun onLogin() {
        presenter.onLogin()
    }

    fun onOriginTextChanged(newText: String) {
        presenter.onOriginTextChanged(newText)
    }

    fun onThemeSelected(theme: RommTheme) {
        presenter.onThemeSelected(theme)
    }

    fun onHideUnsupportedSystemsChanged(hide: Boolean) {
        presenter.onHideUnsupportedSystemsChanged(hide)
    }

    fun onVerifySha1OnLaunchChanged(verify: Boolean) {
        presenter.onVerifySha1OnLaunchChanged(verify)
    }

    fun onAutocleanSavesOnUploadChanged(enabled: Boolean) {
        presenter.onAutocleanSavesOnUploadChanged(enabled)
    }

    fun onOnScreenGameControlsChanged(enabled: Boolean) {
        presenter.onOnScreenGameControlsChanged(enabled)
    }

    fun onUsernameTextChanged(newText: String) {
        presenter.onUsernameTextChanged(newText)
    }

    fun onPasswordTextChanged(newText: String) {
        presenter.onPasswordTextChanged(newText)
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
                getOnScreenGameControlsEnabled = { settingsRepository.onScreenGameControlsEnabled() },
                setOnScreenGameControlsEnabledFn = { enabled -> settingsRepository.setOnScreenGameControlsEnabled(enabled) },
                getTheme = { settingsRepository.theme() },
                setThemeFn = { theme -> settingsRepository.setTheme(theme) },
            ) as T
        }
    }
}
