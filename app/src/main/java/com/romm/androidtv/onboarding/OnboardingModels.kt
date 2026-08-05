package com.romm.androidtv.onboarding

/**
 * Phase 3 onboarding state machine models (spec section 6).
 *
 * Deliberately framework-free (no Compose, no Android ViewModel imports) so the
 * UI state can be produced and asserted by plain JVM unit tests.
 */

/** The three top-level screens of the first-run onboarding flow. */
enum class OnboardingStep { WELCOME, SERVER, CREDENTIALS }

/** Binary in-flight flag for a single long-running action (server validation / login). */
sealed interface AsyncActionState {
    data object Idle : AsyncActionState
    data object Loading : AsyncActionState
}

/**
 * Typed server-validation errors emitted by the ViewModel.
 * The Compose UI maps each variant to a localized string via stringResource().
 */
sealed interface OnboardingServerError {
    data object InvalidAddress : OnboardingServerError
    data object NotRomm : OnboardingServerError
    data object SetupIncomplete : OnboardingServerError
    data object UserpassDisabled : OnboardingServerError
    data object InsecurePublicHttp : OnboardingServerError
    data object PersistenceFailure : OnboardingServerError
}

/**
 * Typed login errors emitted by the ViewModel.
 * The Compose UI maps each variant to a localized string via stringResource().
 */
sealed interface OnboardingLoginError {
    data object InvalidCredentials : OnboardingLoginError
    data object NetworkFailure : OnboardingLoginError
    data object TlsFailure : OnboardingLoginError
    data object ServerFailure : OnboardingLoginError
    data object VerificationFailure : OnboardingLoginError
    data object DeviceCredentialFailure : OnboardingLoginError
    /** Server rejected new token creation because the account is at its device/token cap. */
    data object TokenLimitReached : OnboardingLoginError
    data object RequiredFields : OnboardingLoginError
}

/**
 * Full, immutable snapshot of the onboarding screen state.
 *
 * [serverInput] is the raw (untrimmed) text the user typed; [normalizedOrigin]
 * is the canonical `scheme://host[:port]/basePath` string captured from a
 * successful server validation. The password String lives only in this
 * in-memory state and is cleared on back/success/teardown.
 */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val serverInput: String = "",
    val normalizedOrigin: String? = null,
    val serverError: OnboardingServerError? = null,
    val serverAction: AsyncActionState = AsyncActionState.Idle,
    val username: String = "",
    val password: String = "",
    val loginError: OnboardingLoginError? = null,
    val loginAction: AsyncActionState = AsyncActionState.Idle,
)

/**
 * One-shot navigation/side effect emitted by the ViewModel exactly once.
 * [Completed] signals the whole onboarding flow finished successfully; the
 * Activity observes it and switches to main mode (Phase 5).
 */
enum class OnboardingEffect { Completed }
