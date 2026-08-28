package com.romm.androidtv.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin lifecycle wrapper around the platform-neutral [OnboardingPresenter]
 * (Linux port Phase 4). All state-machine behavior lives in
 * `:shared:presentation`; this class only binds it to the lifecycle owner's
 * scope and forwards the public API so existing call sites (factory,
 * MainActivity) compile unchanged.
 */
class OnboardingViewModel(
    validateRommServer: ValidateRommServer,
    persistValidatedOrigin: PersistValidatedOrigin,
    loginToRomm: LoginToRomm,
    removeOldestClientToken: RemoveOldestClientToken,
    establishKioskSession: EstablishKioskSession,
    beginQrLogin: BeginQrLogin,
    pollQrLogin: PollQrLogin,
    initialServerInput: String,
    initialStep: OnboardingStep = OnboardingStep.WELCOME,
    initialUsername: String = "",
) : ViewModel() {

    private val presenter = OnboardingPresenter(
        scope = viewModelScope,
        validateRommServer = validateRommServer,
        persistValidatedOrigin = persistValidatedOrigin,
        loginToRomm = loginToRomm,
        removeOldestClientToken = removeOldestClientToken,
        establishKioskSession = establishKioskSession,
        beginQrLogin = beginQrLogin,
        pollQrLogin = pollQrLogin,
        initialServerInput = initialServerInput,
        initialStep = initialStep,
        initialUsername = initialUsername,
    )

    val uiState: StateFlow<OnboardingUiState> = presenter.uiState
    val effects: SharedFlow<OnboardingEffect> = presenter.effects

    fun onContinue() {
        presenter.onContinue()
    }

    fun onServerChanged(value: String) {
        presenter.onServerChanged(value)
    }

    fun onValidateServer() {
        presenter.onValidateServer()
    }

    fun onUsernameChanged(value: String) {
        presenter.onUsernameChanged(value)
    }

    fun onPasswordChanged(value: String) {
        presenter.onPasswordChanged(value)
    }

    fun onLogin() {
        presenter.onLogin()
    }

    fun onRemoveOldestDeviceAndRetry() {
        presenter.onRemoveOldestDeviceAndRetry()
    }

    fun onBack() {
        presenter.onBack()
    }

    fun onRetryQrLogin() {
        presenter.onRetryQrLogin()
    }

    override fun onCleared() {
        presenter.onCleared()
        super.onCleared()
    }
}
