package com.romm.androidtv.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.romm.androidtv.auth.LoginCompletionResult
import com.romm.androidtv.auth.ServerValidationResult
import com.romm.androidtv.network.InvalidReason
import com.romm.androidtv.network.RommServerAddress
import com.romm.androidtv.network.ServerAddressResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Narrow functional dependencies injected into [OnboardingViewModel] (spec
 * section 10). Kept as `fun interface`s so the ViewModel never touches OkHttp,
 * repositories, or Android framework objects, and so tests can substitute
 * controllable fakes with zero mocking infrastructure.
 */
fun interface ValidateRommServer {
    suspend operator fun invoke(origin: String): ServerValidationResult
}

fun interface PersistValidatedOrigin {
    suspend operator fun invoke(origin: String): Boolean
}

fun interface LoginToRomm {
    suspend operator fun invoke(origin: String, username: String, password: CharArray): LoginCompletionResult
}

/**
 * User-confirmed remediation for [LoginCompletionResult.TokenLimitReached]: removes the
 * account's single oldest client token to free a slot. Returns whether that succeeded.
 */
fun interface RemoveOldestClientToken {
    suspend operator fun invoke(origin: String): Boolean
}

/**
 * Phase 3 onboarding state machine (spec sections 4.3, 4.4, 6).
 *
 * Owns the three-step WELCOME → SERVER → CREDENTIALS flow. All async work runs
 * in [viewModelScope]. Every network boundary is abstracted behind the three
 * fun interfaces above, and every low-level failure is mapped to a user-facing
 * String here — nothing network-specific ever reaches [OnboardingUiState].
 *
 * Concurrency rules:
 *  - single-flight: a second validate/login while Loading is a no-op;
 *  - editing cancels/invalidates any in-flight action via a generation counter,
 *    so late responses never advance the flow;
 *  - only the in-flight action is disabled.
 *
 * Completion is a one-shot [OnboardingEffect.Completed] emitted exactly once via
 * a buffered [SharedFlow] (not transient state).
 */
class OnboardingViewModel(
    private val validateRommServer: ValidateRommServer,
    private val persistValidatedOrigin: PersistValidatedOrigin,
    private val loginToRomm: LoginToRomm,
    private val removeOldestClientToken: RemoveOldestClientToken,
    initialServerInput: String,
    private val initialStep: OnboardingStep = OnboardingStep.WELCOME,
    private val initialUsername: String = "",
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        OnboardingUiState(
            step = initialStep,
            serverInput = initialServerInput,
            username = initialUsername,
        ),
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<OnboardingEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<OnboardingEffect> = _effects.asSharedFlow()

    /** Incremented on every server edit to invalidate in-flight validation. */
    private var serverValidationGeneration = 0

    /** Incremented on every credential edit to invalidate in-flight login. */
    private var loginGeneration = 0

    /** Guards one-shot completion emission. */
    private var completedEmitted = false

    // ------------------------------------------------------------------ Events

    fun onContinue() {
        val state = _uiState.value
        if (state.step != OnboardingStep.WELCOME) return
        _uiState.update { it.copy(step = OnboardingStep.SERVER) }
    }

    fun onServerChanged(value: String) {
        serverValidationGeneration++
        _uiState.update {
            it.copy(
                serverInput = value,
                serverError = null,
                normalizedOrigin = null,
                serverAction = AsyncActionState.Idle,
            )
        }
    }

    fun onValidateServer() {
        val state = _uiState.value
        if (state.step != OnboardingStep.SERVER) return
        if (state.serverAction == AsyncActionState.Loading) return // single-flight

        val trimmed = state.serverInput.trim()
        when (val parsed = RommServerAddress.parseAndNormalize(trimmed)) {
            is ServerAddressResult.Invalid -> {
                // Local rejection — NO network call.
                val error = if (parsed.reason == InvalidReason.INSECURE_PUBLIC_HTTP) {
                    OnboardingServerError.InsecurePublicHttp
                } else {
                    OnboardingServerError.InvalidAddress
                }
                _uiState.update {
                    it.copy(serverError = error, serverAction = AsyncActionState.Idle)
                }
            }

            is ServerAddressResult.Valid -> {
                val origin = parsed.origin
                val generation = serverValidationGeneration
                _uiState.update {
                    it.copy(serverAction = AsyncActionState.Loading, serverError = null)
                }
                viewModelScope.launch {
                    val result = validateRommServer(origin)
                    handleServerValidationResult(origin, generation, result)
                }
            }
        }
    }

    fun onUsernameChanged(value: String) {
        loginGeneration++
        _uiState.update {
            it.copy(username = value, loginError = null, loginAction = AsyncActionState.Idle)
        }
    }

    fun onPasswordChanged(value: String) {
        loginGeneration++
        _uiState.update {
            it.copy(password = value, loginError = null, loginAction = AsyncActionState.Idle)
        }
    }

    fun onLogin() {
        val state = _uiState.value
        if (state.step != OnboardingStep.CREDENTIALS) return
        if (state.loginAction == AsyncActionState.Loading) return // single-flight

        val origin = state.normalizedOrigin
        val trimmedUsername = state.username.trim()
        if (origin == null || trimmedUsername.isBlank() || state.password.isBlank()) {
            // Local rejection — NO network call.
            _uiState.update {
                it.copy(loginError = OnboardingLoginError.RequiredFields, loginAction = AsyncActionState.Idle)
            }
            return
        }

        val generation = loginGeneration
        _uiState.update { it.copy(loginAction = AsyncActionState.Loading, loginError = null) }

        viewModelScope.launch {
            val passwordChars = state.password.toCharArray()
            val result = loginToRomm(origin, trimmedUsername, passwordChars)
            handleLoginResult(generation, result)
        }
    }

    /**
     * User-confirmed action offered alongside [OnboardingLoginError.TokenLimitReached]:
     * removes the account's oldest client token to free a slot, then retries the exact same
     * login the user just attempted (username/password are still held in state — they are
     * only cleared on success or on navigating back). Never invoked automatically; the user
     * must explicitly tap this, since revoking a token can sign out another device.
     */
    fun onRemoveOldestDeviceAndRetry() {
        val state = _uiState.value
        if (state.step != OnboardingStep.CREDENTIALS) return
        if (state.loginError !is OnboardingLoginError.TokenLimitReached) return
        if (state.loginAction == AsyncActionState.Loading) return // single-flight

        val origin = state.normalizedOrigin
        val trimmedUsername = state.username.trim()
        if (origin == null || trimmedUsername.isBlank() || state.password.isBlank()) {
            _uiState.update {
                it.copy(loginError = OnboardingLoginError.RequiredFields, loginAction = AsyncActionState.Idle)
            }
            return
        }

        val generation = loginGeneration
        _uiState.update { it.copy(loginAction = AsyncActionState.Loading, loginError = null) }

        viewModelScope.launch {
            val removed = removeOldestClientToken(origin)
            if (generation != loginGeneration) return@launch // edited meanwhile — ignore

            if (!removed) {
                // Couldn't free a slot automatically — restore the same error so the user can
                // retry the removal, or back out and manage devices manually on the server.
                _uiState.update {
                    it.copy(loginError = OnboardingLoginError.TokenLimitReached, loginAction = AsyncActionState.Idle)
                }
                return@launch
            }

            val passwordChars = state.password.toCharArray()
            val result = loginToRomm(origin, trimmedUsername, passwordChars)
            handleLoginResult(generation, result)
        }
    }

    fun onBack() {
        val state = _uiState.value
        when (state.step) {
            OnboardingStep.CREDENTIALS -> {
                loginGeneration++
                _uiState.update {
                    it.copy(
                        step = OnboardingStep.SERVER,
                        password = "",
                        loginError = null,
                        loginAction = AsyncActionState.Idle,
                    )
                }
            }

            OnboardingStep.SERVER -> {
                serverValidationGeneration++
                _uiState.update {
                    it.copy(
                        step = OnboardingStep.WELCOME,
                        serverError = null,
                        serverAction = AsyncActionState.Idle,
                        normalizedOrigin = null,
                    )
                }
            }

            OnboardingStep.WELCOME -> {
                // No-op: the Activity finishes itself (Phase 5).
            }
        }
    }

    // ------------------------------------------------------------- Async mapping

    private suspend fun handleServerValidationResult(
        origin: String,
        generation: Int,
        result: ServerValidationResult,
    ) {
        if (generation != serverValidationGeneration) return // stale — ignore
        when (result) {
            is ServerValidationResult.Valid -> {
                val persisted = persistValidatedOrigin(origin)
                if (generation != serverValidationGeneration) return // edited meanwhile
                if (!persisted) {
                    _uiState.update {
                        it.copy(
                            serverError = OnboardingServerError.PersistenceFailure,
                            serverAction = AsyncActionState.Idle,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            normalizedOrigin = origin,
                            step = OnboardingStep.CREDENTIALS,
                            serverError = null,
                            serverAction = AsyncActionState.Idle,
                        )
                    }
                }
            }

            ServerValidationResult.InvalidAddress ->
                setServerError(OnboardingServerError.InvalidAddress)

            ServerValidationResult.NotRomm ->
                setServerError(OnboardingServerError.NotRomm)

            ServerValidationResult.NetworkFailure ->
                setServerError(OnboardingServerError.InvalidAddress)

            ServerValidationResult.TlsFailure ->
                setServerError(OnboardingServerError.InvalidAddress)

            ServerValidationResult.SetupIncomplete ->
                setServerError(OnboardingServerError.SetupIncomplete)

            ServerValidationResult.UserpassDisabled ->
                setServerError(OnboardingServerError.UserpassDisabled)

            ServerValidationResult.InsecurePublicHttp ->
                setServerError(OnboardingServerError.InsecurePublicHttp)
        }
    }

    private fun setServerError(error: OnboardingServerError) {
        _uiState.update {
            it.copy(serverError = error, serverAction = AsyncActionState.Idle)
        }
    }

    private suspend fun handleLoginResult(
        generation: Int,
        result: LoginCompletionResult,
    ) {
        if (generation != loginGeneration) return // stale — ignore
        when (result) {
            is LoginCompletionResult.Success -> {
                if (!completedEmitted) {
                    completedEmitted = true
                    _effects.emit(OnboardingEffect.Completed)
                }
                _uiState.update {
                    it.copy(
                        password = "",
                        loginError = null,
                        loginAction = AsyncActionState.Idle,
                    )
                }
            }

            LoginCompletionResult.InvalidCredentials ->
                setLoginError(OnboardingLoginError.InvalidCredentials)

            LoginCompletionResult.NetworkFailure ->
                setLoginError(OnboardingLoginError.NetworkFailure)

            LoginCompletionResult.TlsFailure ->
                setLoginError(OnboardingLoginError.TlsFailure)

            LoginCompletionResult.ServerFailure ->
                setLoginError(OnboardingLoginError.ServerFailure)

            LoginCompletionResult.VerificationFailure ->
                setLoginError(OnboardingLoginError.VerificationFailure)

            LoginCompletionResult.TokenCreationFailure,
            LoginCompletionResult.TokenVerificationFailure,
            LoginCompletionResult.PersistenceFailure ->
                setLoginError(OnboardingLoginError.DeviceCredentialFailure)

            LoginCompletionResult.TokenLimitReached ->
                setLoginError(OnboardingLoginError.TokenLimitReached)
        }
    }

    /** On credential failure: keep username + masked password, return to Idle. */
    private fun setLoginError(error: OnboardingLoginError) {
        _uiState.update {
            it.copy(loginError = error, loginAction = AsyncActionState.Idle)
        }
    }

    override fun onCleared() {
        // Password String lives only in state; clear it on teardown so it is
        // eligible for GC immediately. The CharArray handed to LoginToRomm is
        // zeroed by the repository (existing behavior).
        _uiState.update { it.copy(password = "") }
        super.onCleared()
    }

}
