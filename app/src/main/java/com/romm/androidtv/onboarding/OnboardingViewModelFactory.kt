package com.romm.androidtv.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * [ViewModelProvider.Factory] that wires the onboarding ViewModel's functional
 * dependencies plus the initial server-input prefill.
 */
class OnboardingViewModelFactory(
    private val validateRommServer: ValidateRommServer,
    private val persistValidatedOrigin: PersistValidatedOrigin,
    private val loginToRomm: LoginToRomm,
    private val removeOldestClientToken: RemoveOldestClientToken,
    private val initialServerInput: String,
    private val initialStep: OnboardingStep = OnboardingStep.WELCOME,
    private val initialUsername: String = "",
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OnboardingViewModel::class.java)) {
            return OnboardingViewModel(
                validateRommServer = validateRommServer,
                persistValidatedOrigin = persistValidatedOrigin,
                loginToRomm = loginToRomm,
                removeOldestClientToken = removeOldestClientToken,
                initialServerInput = initialServerInput,
                initialStep = initialStep,
                initialUsername = initialUsername,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
