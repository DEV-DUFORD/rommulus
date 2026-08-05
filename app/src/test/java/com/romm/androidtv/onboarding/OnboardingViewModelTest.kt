package com.romm.androidtv.onboarding

import com.romm.androidtv.auth.LoginCompletionResult
import com.romm.androidtv.auth.ServerValidationResult
import com.romm.androidtv.model.HeartbeatResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Controllable deferred fakes — no sleeps, no time-based waits. Each network
 * boundary returns a [CompletableDeferred] the test completes manually, so the
 * exact ordering of edit/in-flight/response can be asserted deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("OnboardingViewModel — state machine")
class OnboardingViewModelTest {

    private val heartbeat = HeartbeatResponse(
        version = "v0.17.1",
        setupComplete = true,
        userpassEnabled = true,
        emulatorJsEnabled = false,
    )
    private val canonical = "https://romm.example.com"

    private class ValidateFake : ValidateRommServer {
        var calls = 0
        val origins = mutableListOf<String>()
        val pending = mutableListOf<CompletableDeferred<ServerValidationResult>>()

        override suspend fun invoke(origin: String): ServerValidationResult {
            calls++
            origins.add(origin)
            val deferred = CompletableDeferred<ServerValidationResult>()
            pending.add(deferred)
            return deferred.await()
        }
    }

    private class PersistFake : PersistValidatedOrigin {
        var calls = 0
        var result = true
        val origins = mutableListOf<String>()

        override suspend fun invoke(origin: String): Boolean {
            calls++
            origins.add(origin)
            return result
        }
    }

    private class LoginFake : LoginToRomm {
        var calls = 0
        val args = mutableListOf<Triple<String, String, CharArray>>()
        val pending = mutableListOf<CompletableDeferred<LoginCompletionResult>>()

        override suspend fun invoke(
            origin: String,
            username: String,
            password: CharArray,
        ): LoginCompletionResult {
            calls++
            args.add(Triple(origin, username, password.copyOf()))
            val deferred = CompletableDeferred<LoginCompletionResult>()
            pending.add(deferred)
            return deferred.await()
        }
    }

    private class RemoveOldestClientTokenFake : RemoveOldestClientToken {
        var calls = 0
        val origins = mutableListOf<String>()
        val pending = mutableListOf<CompletableDeferred<Boolean>>()

        override suspend fun invoke(origin: String): Boolean {
            calls++
            origins.add(origin)
            val deferred = CompletableDeferred<Boolean>()
            pending.add(deferred)
            return deferred.await()
        }
    }

    private class Harness(
        val validate: ValidateFake = ValidateFake(),
        val persist: PersistFake = PersistFake(),
        val login: LoginFake = LoginFake(),
        val removeOldestClientToken: RemoveOldestClientTokenFake = RemoveOldestClientTokenFake(),
        initialServerInput: String = "",
        initialStep: OnboardingStep = OnboardingStep.WELCOME,
        initialUsername: String = "",
    ) {
        val vm = OnboardingViewModel(
            validateRommServer = validate,
            persistValidatedOrigin = persist,
            loginToRomm = login,
            removeOldestClientToken = removeOldestClientToken,
            initialServerInput = initialServerInput,
            initialStep = initialStep,
            initialUsername = initialUsername,
        )
    }

    private fun make(
        initialServerInput: String = "",
        initialStep: OnboardingStep = OnboardingStep.WELCOME,
        initialUsername: String = "",
    ): Harness {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        return Harness(
            initialServerInput = initialServerInput,
            initialStep = initialStep,
            initialUsername = initialUsername,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ Basics

    @Test
    fun `initial state is WELCOME with no server prefill`() = runTest {
        val h = make()
        val s = h.vm.uiState.value
        assertThat(s.step).isEqualTo(OnboardingStep.WELCOME)
        assertThat(s.serverInput).isEmpty()
        assertThat(s.normalizedOrigin).isNull()
        assertThat(s.serverAction).isEqualTo(AsyncActionState.Idle)
        assertThat(s.loginAction).isEqualTo(AsyncActionState.Idle)
        assertThat(h.validate.calls).isZero()
        assertThat(h.login.calls).isZero()
    }

    @Test
    fun `initial state carries server prefill`() = runTest {
        val h = make(initialServerInput = "romm.example.com")
        assertThat(h.vm.uiState.value.serverInput).isEqualTo("romm.example.com")
    }

    @Test
    fun `initialStep SERVER starts at SERVER with origin prefilled`() = runTest {
        val h = make(
            initialServerInput = "https://romm.example.com",
            initialStep = OnboardingStep.SERVER,
        )
        val s = h.vm.uiState.value
        assertThat(s.step).isEqualTo(OnboardingStep.SERVER)
        assertThat(s.serverInput).isEqualTo("https://romm.example.com")
        assertThat(s.username).isEmpty()
        assertThat(h.validate.calls).isZero()
        assertThat(h.login.calls).isZero()
    }

    @Test
    fun `initialStep CREDENTIALS prefills username and leaves password empty`() = runTest {
        val h = make(
            initialServerInput = "https://romm.example.com",
            initialStep = OnboardingStep.CREDENTIALS,
            initialUsername = "alice",
        )
        val s = h.vm.uiState.value
        assertThat(s.step).isEqualTo(OnboardingStep.CREDENTIALS)
        assertThat(s.username).isEqualTo("alice")
        assertThat(s.password).isEmpty()
        assertThat(s.serverInput).isEqualTo("https://romm.example.com")
        assertThat(h.login.calls).isZero()
    }

    @Test
    fun `initial username is retained when backing from CREDENTIALS to SERVER`() = runTest {
        val h = make(
            initialServerInput = "https://romm.example.com",
            initialStep = OnboardingStep.CREDENTIALS,
            initialUsername = "alice",
        )
        h.vm.onBack()
        val s = h.vm.uiState.value
        assertThat(s.step).isEqualTo(OnboardingStep.SERVER)
        assertThat(s.username).isEqualTo("alice")
        assertThat(s.password).isEmpty()
        assertThat(s.serverInput).isEqualTo("https://romm.example.com")
    }

    @Test
    fun `onContinue advances to SERVER`() = runTest {
        val h = make()
        h.vm.onContinue()
        assertThat(h.vm.uiState.value.step).isEqualTo(OnboardingStep.SERVER)
    }

    @Test
    fun `onBack from WELCOME is a no-op`() = runTest {
        val h = make()
        h.vm.onBack()
        assertThat(h.vm.uiState.value.step).isEqualTo(OnboardingStep.WELCOME)
    }

    // ------------------------------------------------------- Server validation

    @Test
    fun `valid server persists and advances to CREDENTIALS`() = runTest {
        val h = make()
        h.vm.onContinue()
        h.vm.onServerChanged("https://romm.example.com")
        h.vm.onValidateServer()

        assertThat(h.vm.uiState.value.serverAction).isEqualTo(AsyncActionState.Loading)
        assertThat(h.validate.calls).isEqualTo(1)

        h.validate.pending[0].complete(ServerValidationResult.Valid(canonical, heartbeat))

        val s = h.vm.uiState.value
        assertThat(s.step).isEqualTo(OnboardingStep.CREDENTIALS)
        assertThat(s.normalizedOrigin).isEqualTo(canonical)
        assertThat(s.serverInput).isEqualTo("https://romm.example.com")
        assertThat(s.serverAction).isEqualTo(AsyncActionState.Idle)
        assertThat(s.serverError).isNull()
        assertThat(h.persist.calls).isEqualTo(1)
        assertThat(h.persist.origins).containsExactly(canonical)
    }

    @Test
    fun `blank server makes zero validate calls and shows error`() = runTest {
        val h = make()
        h.vm.onContinue()
        h.vm.onValidateServer() // serverInput blank

        val s = h.vm.uiState.value
        assertThat(s.step).isEqualTo(OnboardingStep.SERVER)
        assertThat(s.serverError).isEqualTo(OnboardingServerError.InvalidAddress)
        assertThat(h.validate.calls).isZero()
        assertThat(h.persist.calls).isZero()
    }

    @Test
    fun `bare hostname infers https and triggers network validation`() = runTest {
        val h = make()
        h.vm.onContinue()
        h.vm.onServerChanged("romm.example.com") // no scheme — infers https://
        h.vm.onValidateServer()

        assertThat(h.vm.uiState.value.serverAction).isEqualTo(AsyncActionState.Loading)
        assertThat(h.validate.calls).isEqualTo(1)
        // The inferred canonical origin is used for the network call.
        assertThat(h.validate.origins).containsExactly("https://romm.example.com")

        h.validate.pending[0].complete(ServerValidationResult.Valid(canonical, heartbeat))

        val s = h.vm.uiState.value
        assertThat(s.step).isEqualTo(OnboardingStep.CREDENTIALS)
        assertThat(s.normalizedOrigin).isEqualTo("https://romm.example.com")
        assertThat(s.serverError).isNull()
    }

    @Test
    fun `public HTTP makes zero validate calls and shows public-HTTP error`() = runTest {
        val h = make()
        h.vm.onContinue()
        h.vm.onServerChanged("http://example.com") // public host over http
        h.vm.onValidateServer()

        val s = h.vm.uiState.value
        assertThat(s.serverError).isEqualTo(OnboardingServerError.InsecurePublicHttp)
        assertThat(h.validate.calls).isZero()
    }

    @Test
    fun `InvalidAddress maps to generic invalid-server error`() = runTest {
        val h = make()
        goToServerValidation(h)
        h.validate.pending[0].complete(ServerValidationResult.InvalidAddress)
        assertError(h, OnboardingServerError.InvalidAddress)
    }

    @Test
    fun `NotRomm maps to NotRomm error`() = runTest {
        val h = make()
        goToServerValidation(h)
        h.validate.pending[0].complete(ServerValidationResult.NotRomm)
        assertError(h, OnboardingServerError.NotRomm)
    }

    @Test
    fun `NetworkFailure maps to generic invalid-server error`() = runTest {
        val h = make()
        goToServerValidation(h)
        h.validate.pending[0].complete(ServerValidationResult.NetworkFailure)
        assertError(h, OnboardingServerError.InvalidAddress)
    }

    @Test
    fun `TlsFailure maps to generic invalid-server error`() = runTest {
        val h = make()
        goToServerValidation(h)
        h.validate.pending[0].complete(ServerValidationResult.TlsFailure)
        assertError(h, OnboardingServerError.InvalidAddress)
    }

    @Test
    fun `InsecurePublicHttp maps to public-HTTP error`() = runTest {
        val h = make()
        goToServerValidation(h)
        h.validate.pending[0].complete(ServerValidationResult.InsecurePublicHttp)
        assertError(h, OnboardingServerError.InsecurePublicHttp)
    }

    @Test
    fun `SetupIncomplete maps to setup-not-completed error`() = runTest {
        val h = make()
        goToServerValidation(h)
        h.validate.pending[0].complete(ServerValidationResult.SetupIncomplete)
        assertError(h, OnboardingServerError.SetupIncomplete)
    }

    @Test
    fun `UserpassDisabled maps to login-disabled error`() = runTest {
        val h = make()
        goToServerValidation(h)
        h.validate.pending[0].complete(ServerValidationResult.UserpassDisabled)
        assertError(h, OnboardingServerError.UserpassDisabled)
    }

    @Test
    fun `persistence failure stays on SERVER`() = runTest {
        val h = make()
        h.persist.result = false
        goToServerValidation(h)
        h.validate.pending[0].complete(ServerValidationResult.Valid(canonical, heartbeat))

        val s = h.vm.uiState.value
        assertThat(s.step).isEqualTo(OnboardingStep.SERVER)
        assertThat(s.serverError).isEqualTo(OnboardingServerError.PersistenceFailure)
        assertThat(s.normalizedOrigin).isNull()
        assertThat(h.persist.calls).isEqualTo(1)
    }

    @Test
    fun `duplicate Next makes exactly one validate call`() = runTest {
        val h = make()
        h.vm.onContinue()
        h.vm.onServerChanged("https://romm.example.com")
        h.vm.onValidateServer()
        h.vm.onValidateServer() // ignored while Loading
        h.vm.onValidateServer()

        assertThat(h.validate.calls).isEqualTo(1)
        h.validate.pending[0].complete(ServerValidationResult.Valid(canonical, heartbeat))
        assertThat(h.validate.calls).isEqualTo(1)
        assertThat(h.vm.uiState.value.step).isEqualTo(OnboardingStep.CREDENTIALS)
    }

    @Test
    fun `changed server while in-flight - stale response does not advance`() = runTest {
        val h = make()
        h.vm.onContinue()
        h.vm.onServerChanged("https://romm.example.com")
        h.vm.onValidateServer()
        assertThat(h.validate.calls).isEqualTo(1)

        // User edits before the network responds.
        h.vm.onServerChanged("https://romm.example.com/other")
        h.validate.pending[0].complete(ServerValidationResult.Valid(canonical, heartbeat))

        val s = h.vm.uiState.value
        assertThat(s.step).isEqualTo(OnboardingStep.SERVER)
        assertThat(s.normalizedOrigin).isNull()
        assertThat(s.serverAction).isEqualTo(AsyncActionState.Idle)
        assertThat(h.persist.calls).isZero()
    }

    // ------------------------------------------------------------- Credentials

    @Test
    fun `empty credentials rejected locally with no login call`() = runTest {
        val h = make()
        goToCredentials(h)
        h.vm.onLogin()

        val s = h.vm.uiState.value
        assertThat(s.loginError).isEqualTo(OnboardingLoginError.RequiredFields)
        assertThat(h.login.calls).isZero()
    }

    @Test
    fun `invalid credentials emits typed error (UI interpolates host)`() = runTest {
        val h = make()
        goToCredentials(h)
        h.vm.onUsernameChanged("zack")
        h.vm.onPasswordChanged("secret")
        h.vm.onLogin()
        h.login.pending[0].complete(LoginCompletionResult.InvalidCredentials)

        val s = h.vm.uiState.value
        assertThat(s.loginError).isEqualTo(OnboardingLoginError.InvalidCredentials)
        assertThat(s.username).isEqualTo("zack")
        assertThat(s.password).isEqualTo("secret") // kept so user can correct only username
        assertThat(s.loginAction).isEqualTo(AsyncActionState.Idle)
    }

    @Test
    fun `NetworkFailure maps to typed NetworkFailure error`() = runTest {
        val h = make()
        goToCredentials(h)
        typeCredentialsAndLogin(h)
        h.login.pending[0].complete(LoginCompletionResult.NetworkFailure)
        assertLoginError(h, OnboardingLoginError.NetworkFailure)
    }

    @Test
    fun `TlsFailure maps to typed TlsFailure error`() = runTest {
        val h = make()
        goToCredentials(h)
        typeCredentialsAndLogin(h)
        h.login.pending[0].complete(LoginCompletionResult.TlsFailure)
        assertLoginError(h, OnboardingLoginError.TlsFailure)
    }

    @Test
    fun `ServerFailure maps to typed ServerFailure error`() = runTest {
        val h = make()
        goToCredentials(h)
        typeCredentialsAndLogin(h)
        h.login.pending[0].complete(LoginCompletionResult.ServerFailure)
        assertLoginError(h, OnboardingLoginError.ServerFailure)
    }

    @Test
    fun `VerificationFailure maps to typed VerificationFailure error`() = runTest {
        val h = make()
        goToCredentials(h)
        typeCredentialsAndLogin(h)
        h.login.pending[0].complete(LoginCompletionResult.VerificationFailure)
        assertLoginError(h, OnboardingLoginError.VerificationFailure)
    }

    @Test
    fun `TokenCreationFailure maps to DeviceCredentialFailure error`() = runTest {
        val h = make()
        goToCredentials(h)
        typeCredentialsAndLogin(h)
        h.login.pending[0].complete(LoginCompletionResult.TokenCreationFailure)
        assertLoginError(h, OnboardingLoginError.DeviceCredentialFailure)
    }

    @Test
    fun `TokenVerificationFailure maps to DeviceCredentialFailure error`() = runTest {
        val h = make()
        goToCredentials(h)
        typeCredentialsAndLogin(h)
        h.login.pending[0].complete(LoginCompletionResult.TokenVerificationFailure)
        assertLoginError(h, OnboardingLoginError.DeviceCredentialFailure)
    }

    @Test
    fun `PersistenceFailure maps to DeviceCredentialFailure error`() = runTest {
        val h = make()
        goToCredentials(h)
        typeCredentialsAndLogin(h)
        h.login.pending[0].complete(LoginCompletionResult.PersistenceFailure)
        assertLoginError(h, OnboardingLoginError.DeviceCredentialFailure)
    }

    @Test
    fun `TokenLimitReached maps to its own distinct error, not DeviceCredentialFailure`() = runTest {
        val h = make()
        goToCredentials(h)
        typeCredentialsAndLogin(h)
        h.login.pending[0].complete(LoginCompletionResult.TokenLimitReached)
        assertLoginError(h, OnboardingLoginError.TokenLimitReached)
    }

    @Test
    fun `remove oldest device succeeds then retries login and completes`() = runTest {
        val h = make()
        goToCredentials(h)
        typeCredentialsAndLogin(h)
        h.login.pending[0].complete(LoginCompletionResult.TokenLimitReached)
        assertLoginError(h, OnboardingLoginError.TokenLimitReached)

        val collected = mutableListOf<OnboardingEffect>()
        val collector = launch { h.vm.effects.collect { collected.add(it) } }
        runCurrent()

        h.vm.onRemoveOldestDeviceAndRetry()
        assertThat(h.vm.uiState.value.loginAction).isEqualTo(AsyncActionState.Loading)
        assertThat(h.vm.uiState.value.loginError).isNull()

        h.removeOldestClientToken.pending[0].complete(true)
        runCurrent()
        assertThat(h.login.calls).isEqualTo(2) // original attempt + retry
        assertThat(h.login.args[1].second).isEqualTo("zack") // retried with same credentials

        h.login.pending[1].complete(LoginCompletionResult.Success(verifiedUser(), durableToken()))
        advanceUntilIdle()
        assertThat(collected).containsExactly(OnboardingEffect.Completed)
        collector.cancel()
    }

    @Test
    fun `remove oldest device fails and re-shows TokenLimitReached without retrying login`() = runTest {
        val h = make()
        goToCredentials(h)
        typeCredentialsAndLogin(h)
        h.login.pending[0].complete(LoginCompletionResult.TokenLimitReached)

        h.vm.onRemoveOldestDeviceAndRetry()
        h.removeOldestClientToken.pending[0].complete(false)
        runCurrent()

        assertThat(h.login.calls).isEqualTo(1) // no retry attempted
        assertThat(h.vm.uiState.value.loginError).isEqualTo(OnboardingLoginError.TokenLimitReached)
        assertThat(h.vm.uiState.value.loginAction).isEqualTo(AsyncActionState.Idle)
    }

    @Test
    fun `remove oldest device is a no-op unless a TokenLimitReached error is showing`() = runTest {
        val h = make()
        goToCredentials(h)
        typeCredentialsAndLogin(h)
        h.login.pending[0].complete(LoginCompletionResult.InvalidCredentials)

        h.vm.onRemoveOldestDeviceAndRetry()
        assertThat(h.removeOldestClientToken.calls).isZero()
    }

    @Test
    fun `duplicate Login makes exactly one login call`() = runTest {
        val h = make()
        goToCredentials(h)
        h.vm.onUsernameChanged("zack")
        h.vm.onPasswordChanged("secret")
        h.vm.onLogin()
        h.vm.onLogin() // ignored while Loading
        h.vm.onLogin()

        assertThat(h.login.calls).isEqualTo(1)
        h.login.pending[0].complete(LoginCompletionResult.InvalidCredentials)
        assertThat(h.login.calls).isEqualTo(1)
    }

    @Test
    fun `changed credentials in-flight - stale login response ignored`() = runTest {
        val h = make()
        goToCredentials(h)
        h.vm.onUsernameChanged("zack")
        h.vm.onPasswordChanged("secret")
        h.vm.onLogin()
        assertThat(h.login.calls).isEqualTo(1)

        // User edits credentials before the network responds.
        h.vm.onPasswordChanged("new-password")
        h.login.pending[0].complete(LoginCompletionResult.Success(verifiedUser(), durableToken()))

        val s = h.vm.uiState.value
        assertThat(s.step).isEqualTo(OnboardingStep.CREDENTIALS)
        assertThat(s.loginError).isNull()
        assertThat(s.loginAction).isEqualTo(AsyncActionState.Idle)
        assertThat(s.password).isEqualTo("new-password")
    }

    @Test
    fun `login CharArray handed to LoginToRomm contains expected characters`() = runTest {
        val h = make()
        goToCredentials(h)
        h.vm.onUsernameChanged("  zack  ")
        h.vm.onPasswordChanged("s3cret pass")
        h.vm.onLogin()

        assertThat(h.login.args).hasSize(1)
        val (origin, username, password) = h.login.args[0]
        assertThat(origin).isEqualTo(canonical)
        assertThat(username).isEqualTo("zack") // trimmed
        assertThat(password).isEqualTo("s3cret pass".toCharArray())
    }

    // -------------------------------------------------------- Completion effect

    @Test
    fun `success emits Completed exactly once and clears password`() = runTest {
        val h = make()
        goToCredentials(h)
        typeCredentialsAndLogin(h)

        val collected = mutableListOf<OnboardingEffect>()
        val collector = launch {
            h.vm.effects.collect { collected.add(it) }
        }
        runCurrent() // start collector subscription before completing login

        h.login.pending[0].complete(LoginCompletionResult.Success(verifiedUser(), durableToken()))
        advanceUntilIdle()

        assertThat(collected).containsExactly(OnboardingEffect.Completed)
        val s = h.vm.uiState.value
        assertThat(s.password).isEmpty()
        assertThat(s.loginError).isNull()
        assertThat(s.loginAction).isEqualTo(AsyncActionState.Idle)

        // Press login again — must not re-emit Completed.
        typeCredentialsAndLogin(h)
        h.login.pending[1].complete(LoginCompletionResult.Success(verifiedUser(), durableToken()))
        advanceUntilIdle()
        assertThat(collected).containsExactly(OnboardingEffect.Completed)
        collector.cancel()
    }

    // ------------------------------------------------------------------- Back

    @Test
    fun `Back from CREDENTIALS clears password retains username`() = runTest {
        val h = make()
        goToCredentials(h)
        h.vm.onUsernameChanged("zack")
        h.vm.onPasswordChanged("secret")
        h.vm.onBack()

        val s = h.vm.uiState.value
        assertThat(s.step).isEqualTo(OnboardingStep.SERVER)
        assertThat(s.password).isEmpty()
        assertThat(s.username).isEqualTo("zack")
        assertThat(s.loginError).isNull()
    }

    @Test
    fun `Back from SERVER returns to WELCOME retaining serverInput`() = runTest {
        val h = make()
        h.vm.onContinue()
        h.vm.onServerChanged("https://romm.example.com")
        h.vm.onBack()

        val s = h.vm.uiState.value
        assertThat(s.step).isEqualTo(OnboardingStep.WELCOME)
        assertThat(s.serverInput).isEqualTo("https://romm.example.com")
    }

    @Test
    fun `Back remains available while either action is Loading`() = runTest {
        val h = make()
        h.vm.onContinue()
        h.vm.onServerChanged("https://romm.example.com")
        h.vm.onValidateServer()
        assertThat(h.vm.uiState.value.serverAction).isEqualTo(AsyncActionState.Loading)

        h.vm.onBack() // SERVER -> WELCOME while loading
        assertThat(h.vm.uiState.value.step).isEqualTo(OnboardingStep.WELCOME)
    }

    // ---------------------------------------------------------------- Helpers

    /** SERVER step with a pending validation of the canonical address. */
    private fun goToServerValidation(h: Harness) {
        h.vm.onContinue()
        h.vm.onServerChanged("https://romm.example.com")
        h.vm.onValidateServer()
        assertThat(h.validate.calls).isEqualTo(1)
    }

    /** Full trip to CREDENTIALS with normalizedOrigin set. */
    private fun goToCredentials(h: Harness) {
        goToServerValidation(h)
        h.validate.pending[0].complete(ServerValidationResult.Valid(canonical, heartbeat))
        assertThat(h.vm.uiState.value.step).isEqualTo(OnboardingStep.CREDENTIALS)
    }

    private fun typeCredentialsAndLogin(h: Harness) {
        h.vm.onUsernameChanged("zack")
        h.vm.onPasswordChanged("secret")
        h.vm.onLogin()
    }

    private fun assertError(h: Harness, expected: OnboardingServerError) {
        val s = h.vm.uiState.value
        assertThat(s.serverError).isEqualTo(expected)
        assertThat(s.step).isEqualTo(OnboardingStep.SERVER)
        assertThat(s.serverAction).isEqualTo(AsyncActionState.Idle)
    }

    private fun assertLoginError(h: Harness, expected: OnboardingLoginError) {
        val s = h.vm.uiState.value
        assertThat(s.loginError).isEqualTo(expected)
        assertThat(s.loginAction).isEqualTo(AsyncActionState.Idle)
    }

    private fun verifiedUser() = com.romm.androidtv.network.VerifiedUser(username = "zack", isAdmin = false)

    private fun durableToken(): com.romm.androidtv.romm.ClientToken {
        // ClientToken is a data class wrapping a raw token string; build a minimal one.
        return com.romm.androidtv.romm.ClientToken(raw = "durable-token")
    }
}
