package com.romm.androidtv.library

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.config.ServerProfile
import com.romm.androidtv.network.HeartbeatCallResult
import com.romm.androidtv.model.HeartbeatError
import com.romm.androidtv.model.HeartbeatResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * JVM unit tests for [SettingsViewModel]: origin validation, save/restore state
 * transitions, connection check states, and session invalidation on origin change.
 *
 * Uses functional lambdas as dependency mocks — no concrete class extension needed.
 */
@DisplayName("SettingsViewModel — validation, save, restore, connection, session")
class SettingsViewModelTest {

    private lateinit var testJob: Job
    private lateinit var testScope: CoroutineScope

    @BeforeEach
    fun setUp() {
        testJob = Job()
        testScope = CoroutineScope(Dispatchers.Unconfined + testJob)
        // ViewModel.viewModelScope requires a working Dispatchers.Main even when a coroutine is
        // launched onto another dispatcher — there is no Robolectric/Android runtime in this
        // plain JVM unit test, so the real Main dispatcher is unavailable and must be substituted.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        testJob.cancel()
        Dispatchers.resetMain()
    }

    // ---- Helper to construct VM with configurable mocks ----

    private data class TestMocks(
        var storedOrigin: String = "",
        var sessionRecord: SessionStore.Record? = null,
        var invalidated: Boolean = false,
        var heartbeatCalls: Int = 0,
        var loginCalls: Int = 0,
        var loginSuccessInvoked: Boolean = false,
        var verifySha1OnLaunch: Boolean = false,
        var autocleanSavesOnUpload: Boolean = true,
    )

    private fun makeViewModel(
        initialOrigin: String = "",
        initialSession: SessionStore.Record? = null,
        buildDefault: String = "https://build-default.example.com",
        heartbeatResult: HeartbeatCallResult = HeartbeatCallResult.Failure(HeartbeatError.NETWORK_ERROR),
        loginResult: com.romm.androidtv.network.AuthFlowResult = com.romm.androidtv.network.AuthFlowResult.Failure(
            com.romm.androidtv.network.AuthError.NETWORK_ERROR,
        ),
    ): Pair<SettingsViewModel, TestMocks> {
        val mocks = TestMocks(
            storedOrigin = initialOrigin,
            sessionRecord = initialSession,
        )

        // Mutable references so lambdas capture the latest value
        @Suppress("UNUSED_ANONYMOUS_PARAMETER")
        val vm = SettingsViewModel(
            getCurrentProfile = { ServerProfile(origin = if (mocks.storedOrigin.isBlank()) buildDefault else mocks.storedOrigin) },
            setOriginFn = { origin -> mocks.storedOrigin = if (origin.isBlank()) "" else origin },
            clearOverrideFn = { mocks.storedOrigin = "" },
            getSessionRecord = { mocks.sessionRecord },
            clearSessionFn = { mocks.sessionRecord = null },
            checkHeartbeatFn = { _ -> mocks.heartbeatCalls++; heartbeatResult },
            loginFn = { _, _, _ -> mocks.loginCalls++; loginResult },
            onLoginSuccess = { mocks.loginSuccessInvoked = true },
            buildDefaultOrigin = buildDefault,
            onSessionInvalidated = { mocks.invalidated = true },
            getVerifySha1OnLaunch = { mocks.verifySha1OnLaunch },
            setVerifySha1OnLaunchFn = { verify -> mocks.verifySha1OnLaunch = verify },
            getAutocleanSavesOnUpload = { mocks.autocleanSavesOnUpload },
            setAutocleanSavesOnUploadFn = { enabled -> mocks.autocleanSavesOnUpload = enabled },
        )

        return vm to mocks
    }

    // ---- Tests ----

    @Test
    fun `initial state loads persisted origin and session info`() = runBlocking {
        val (vm, mocks) = makeViewModel(
            initialOrigin = "https://persisted.example.com",
            initialSession = SessionStore.Record("https://persisted.example.com", "testuser", System.currentTimeMillis()),
        )

        val state = vm.uiState.value
        assertThat(state.originText).isEqualTo("https://persisted.example.com")
        assertThat(state.currentOrigin).isEqualTo("https://persisted.example.com")
        assertThat(state.currentUsername).isEqualTo("testuser")
        assertThat(state.validationError).isNull()
        assertThat(mocks.invalidated).isFalse()
    }

    @Test
    fun `validateOrigin rejects blank string`() = runBlocking {
        val (vm, _) = makeViewModel()
        assertThat(vm.validateOrigin("")).isEqualTo("Server address is required")
    }

    @Test
    fun `validateOrigin rejects whitespace-only string`() = runBlocking {
        val (vm, _) = makeViewModel()
        assertThat(vm.validateOrigin("   ")).isEqualTo("Server address is required")
    }

    @Test
    fun `validateOrigin rejects invalid URL format`() = runBlocking {
        val (vm, _) = makeViewModel()
        assertThat(vm.validateOrigin("https://exa mple.com")).isEqualTo("Invalid URL format")
    }

    @Test
    fun `validateOrigin rejects unsupported scheme`() = runBlocking {
        val (vm, _) = makeViewModel()
        assertThat(vm.validateOrigin("ftp://example.com")).isEqualTo("Only HTTP and HTTPS schemes are supported")
    }

    @Test
    fun `validateOrigin accepts valid https URL`() = runBlocking {
        val (vm, _) = makeViewModel()
        assertThat(vm.validateOrigin("https://romm.example.com")).isNull()
    }

    @Test
    fun `validateOrigin accepts valid http URL`() = runBlocking {
        val (vm, _) = makeViewModel()
        assertThat(vm.validateOrigin("http://localhost:8080")).isNull()
    }

    @Test
    fun `validateOrigin accepts URL with subpath`() = runBlocking {
        val (vm, _) = makeViewModel()
        assertThat(vm.validateOrigin("https://example.com/romm")).isNull()
    }

    // ---- onOriginTextChanged tests ----

    @Test
    fun `onOriginTextChanged updates text and clears previous feedback`() = runBlocking {
        val (vm, _) = makeViewModel(initialOrigin = "https://default.example.com")
        vm.onOriginTextChanged("https://new.example.com")

        val state = vm.uiState.value
        assertThat(state.originText).isEqualTo("https://new.example.com")
        assertThat(state.validationError).isNull()
        assertThat(state.saveSuccessMessage).isNull()
        assertThat(state.saveErrorMessage).isNull()
    }

    @Test
    fun `onOriginTextChanged sets validation error for invalid input`() = runBlocking {
        val (vm, _) = makeViewModel(initialOrigin = "https://default.example.com")
        vm.onOriginTextChanged("https://exa mple.com")

        val state = vm.uiState.value
        assertThat(state.validationError).isEqualTo("Invalid URL format")
    }

    @Test
    fun `onOriginTextChanged detects origin changed`() = runBlocking {
        val (vm, _) = makeViewModel(initialOrigin = "https://default.example.com")
        assertThat(vm.uiState.value.originChanged).isFalse()

        vm.onOriginTextChanged("https://new.example.com")
        assertThat(vm.uiState.value.originChanged).isTrue()
    }

    // ---- onSave tests ----

    @Test
    fun `onSave with invalid origin shows error and does not persist`() = runBlocking {
        val (vm, mocks) = makeViewModel(initialOrigin = "https://current.example.com")
        vm.onOriginTextChanged("https://exa mple.com")
        vm.onSave()

        val state = vm.uiState.value
        assertThat(state.validationError).isEqualTo("Invalid URL format")
        assertThat(state.saveErrorMessage).isNotNull()
        assertThat(state.saveSuccessMessage).isNull()
        assertThat(mocks.storedOrigin).isEqualTo("https://current.example.com")
    }

    @Test
    fun `onSave with valid unchanged origin shows no changes`() = runBlocking {
        val (vm, mocks) = makeViewModel(
            initialOrigin = "https://current.example.com",
            initialSession = SessionStore.Record("https://current.example.com", "user", System.currentTimeMillis()),
        )
        vm.onSave()

        val state = vm.uiState.value
        assertThat(state.saveSuccessMessage).isEqualTo("No changes")
        assertThat(mocks.invalidated).isFalse()
    }

    @Test
    fun `onSave with changed origin persists and invalidates session`() = runBlocking {
        val (vm, mocks) = makeViewModel(
            initialOrigin = "https://old.example.com",
            initialSession = SessionStore.Record("https://old.example.com", "user", System.currentTimeMillis()),
        )

        vm.onOriginTextChanged("https://new.example.com")
        vm.onSave()

        // Origin persisted
        assertThat(mocks.storedOrigin).isEqualTo("https://new.example.com")
        // Session cleared
        assertThat(mocks.sessionRecord).isNull()
        // Callback fired
        assertThat(mocks.invalidated).isTrue()
        // Success message shown
        val state = vm.uiState.value
        assertThat(state.saveSuccessMessage).contains("session cleared")
    }

    @Test
    fun `onSave with same origin normalized does not invalidate session`() = runBlocking {
        val (vm, mocks) = makeViewModel(
            initialOrigin = "https://current.example.com",
            initialSession = SessionStore.Record("https://current.example.com", "user", System.currentTimeMillis()),
        )

        // Edit to equivalent URL (same origin with explicit default port)
        vm.onOriginTextChanged("https://current.example.com:443")
        vm.onSave()

        assertThat(mocks.invalidated).isFalse()
        val state = vm.uiState.value
        assertThat(state.saveSuccessMessage).isEqualTo("Saved")
    }

    // ---- onRestoreDefault tests ----

    @Test
    fun `onRestoreDefault clears override and shows build default`() = runBlocking {
        val (vm, mocks) = makeViewModel(initialOrigin = "https://custom.example.com")
        vm.onRestoreDefault()

        assertThat(mocks.storedOrigin).isEmpty()
        val state = vm.uiState.value
        assertThat(state.originText).isEqualTo("https://build-default.example.com")
        assertThat(state.saveSuccessMessage).isEqualTo("Restored to build default")
    }

    @Test
    fun `onRestoreDefault with changed origin and active session invalidates session`() = runBlocking {
        val (vm, mocks) = makeViewModel(
            initialOrigin = "https://custom.example.com",
            initialSession = SessionStore.Record("https://custom.example.com", "user", System.currentTimeMillis()),
        )

        vm.onRestoreDefault()

        assertThat(mocks.storedOrigin).isEmpty()
        assertThat(mocks.sessionRecord).isNull()
        assertThat(mocks.invalidated).isTrue()
        val state = vm.uiState.value
        assertThat(state.originText).isEqualTo("https://build-default.example.com")
        assertThat(state.saveSuccessMessage).contains("session cleared")
    }

    @Test
    fun `onRestoreDefault with same origin normalized does not invalidate session`() = runBlocking {
        val (vm, mocks) = makeViewModel(
            initialOrigin = "https://build-default.example.com",
            initialSession = SessionStore.Record("https://build-default.example.com", "user", System.currentTimeMillis()),
        )

        vm.onRestoreDefault()

        assertThat(mocks.invalidated).isFalse()
        assertThat(mocks.sessionRecord).isEqualTo(SessionStore.Record("https://build-default.example.com", "user", System.currentTimeMillis()))
        val state = vm.uiState.value
        assertThat(state.saveSuccessMessage).isEqualTo("Restored to build default")
    }

    // ---- onCheckConnection tests ----

    @Test
    fun `onCheckConnection with invalid origin shows error immediately`() = runBlocking {
        val (vm, mocks) = makeViewModel()
        vm.onOriginTextChanged("https://exa mple.com")
        vm.onCheckConnection()

        val state = vm.uiState.value
        assertThat(state.connectionCheck).isInstanceOf(ConnectionCheckState.Error::class.java)
        assertThat((state.connectionCheck as ConnectionCheckState.Error).message).isEqualTo("Invalid URL format")
        assertThat(mocks.heartbeatCalls).isEqualTo(0) // No network call made
    }

    @Test
    fun `onCheckConnection with valid origin fires heartbeat and shows success`() = runBlocking {
        val (vm, mocks) = makeViewModel(
            heartbeatResult = HeartbeatCallResult.Success(HeartbeatResponse("1.2.3", true, true, true)),
        )
        vm.onOriginTextChanged("https://example.com")
        vm.onCheckConnection()

        assertThat(mocks.heartbeatCalls).isEqualTo(1)
        val state = vm.uiState.value
        assertThat(state.connectionCheck).isInstanceOf(ConnectionCheckState.Success::class.java)
        assertThat((state.connectionCheck as ConnectionCheckState.Success).version).isEqualTo("1.2.3")
    }

    @Test
    fun `onCheckConnection formats heartbeat error`() = runBlocking {
        val (vm, _) = makeViewModel(
            heartbeatResult = HeartbeatCallResult.Failure(HeartbeatError.NETWORK_ERROR),
        )
        vm.onOriginTextChanged("https://unreachable.example.com")
        vm.onCheckConnection()

        val state = vm.uiState.value
        assertThat(state.connectionCheck).isInstanceOf(ConnectionCheckState.Error::class.java)
        assertThat((state.connectionCheck as ConnectionCheckState.Error).message).isEqualTo("Network unreachable")
    }

    // ---- Session invalidation edge cases ----

    @Test
    fun `onSave with changed origin but no session does not call invalidation callback`() = runBlocking {
        val (vm, mocks) = makeViewModel(initialOrigin = "https://old.example.com")
        // No session record

        vm.onOriginTextChanged("https://new.example.com")
        vm.onSave()

        assertThat(mocks.invalidated).isFalse()
        val state = vm.uiState.value
        assertThat(state.saveSuccessMessage).isEqualTo("Saved")
    }

    @Test
    fun `connection check TLS error is formatted`() = runBlocking {
        val (vm, _) = makeViewModel(
            heartbeatResult = HeartbeatCallResult.Failure(HeartbeatError.TLS_ERROR),
        )
        vm.onOriginTextChanged("https://bad-cert.example.com")
        vm.onCheckConnection()

        val state = vm.uiState.value
        assertThat(state.connectionCheck).isInstanceOf(ConnectionCheckState.Error::class.java)
        assertThat((state.connectionCheck as ConnectionCheckState.Error).message).isEqualTo("TLS / certificate error")
    }

    @Test
    fun `connection check HTTP error is formatted`() = runBlocking {
        val (vm, _) = makeViewModel(
            heartbeatResult = HeartbeatCallResult.Failure(HeartbeatError.HTTP_ERROR),
        )
        vm.onOriginTextChanged("https://example.com")
        vm.onCheckConnection()

        val state = vm.uiState.value
        assertThat(state.connectionCheck).isInstanceOf(ConnectionCheckState.Error::class.java)
        assertThat((state.connectionCheck as ConnectionCheckState.Error).message).isEqualTo("HTTP error from server")
    }

    // ---- Native credentials login (Settings screen) ----

    @Test
    fun `login succeeds, clears password, and notifies onLoginSuccess`() = runBlocking {
        val (vm, mocks) = makeViewModel(
            initialOrigin = "https://romm.example.com",
            loginResult = com.romm.androidtv.network.AuthFlowResult.Success(
                heartbeatAfterLogin = com.romm.androidtv.model.HeartbeatResponse(
                    version = "5.0.0", setupComplete = true, userpassEnabled = true, emulatorJsEnabled = true,
                ),
                verifiedUser = com.romm.androidtv.network.VerifiedUser(username = "root", isAdmin = true),
            ),
        )

        vm.onUsernameTextChanged("root")
        vm.onPasswordTextChanged("hunter2")
        vm.onLogin()

        val state = vm.uiState.value
        assertThat(mocks.loginCalls).isEqualTo(1)
        assertThat(mocks.loginSuccessInvoked).isTrue()
        assertThat(state.loginState).isInstanceOf(SettingsLoginState.Success::class.java)
        assertThat(state.passwordText).isEmpty()
        assertThat(state.currentUsername).isEqualTo("root")
    }

    @Test
    fun `login failure surfaces a formatted error and clears password without navigating`() = runBlocking {
        val (vm, mocks) = makeViewModel(
            initialOrigin = "https://romm.example.com",
            loginResult = com.romm.androidtv.network.AuthFlowResult.Failure(
                com.romm.androidtv.network.AuthError.INVALID_CREDENTIALS,
            ),
        )

        vm.onUsernameTextChanged("root")
        vm.onPasswordTextChanged("wrong")
        vm.onLogin()

        val state = vm.uiState.value
        assertThat(mocks.loginSuccessInvoked).isFalse()
        assertThat(state.loginState).isInstanceOf(SettingsLoginState.Error::class.java)
        assertThat((state.loginState as SettingsLoginState.Error).message).isEqualTo("Invalid username or password")
        assertThat(state.passwordText).isEmpty()
    }

    @Test
    fun `login with blank username or password is rejected without a network call`() = runBlocking {
        val (vm, mocks) = makeViewModel(initialOrigin = "https://romm.example.com")

        vm.onUsernameTextChanged("")
        vm.onPasswordTextChanged("")
        vm.onLogin()

        assertThat(mocks.loginCalls).isEqualTo(0)
        assertThat(vm.uiState.value.loginState).isInstanceOf(SettingsLoginState.Error::class.java)
    }

    @Test
    fun `login with an invalid origin is rejected without a network call`() = runBlocking {
        val (vm, mocks) = makeViewModel(initialOrigin = "")

        vm.onUsernameTextChanged("root")
        vm.onPasswordTextChanged("hunter2")
        vm.onLogin()

        assertThat(mocks.loginCalls).isEqualTo(0)
        assertThat(vm.uiState.value.loginState).isInstanceOf(SettingsLoginState.Error::class.java)
    }

    @Test
    fun `initial state loads verifySha1OnLaunch as false when nothing is persisted`() = runBlocking {
        val (vm, _) = makeViewModel()

        assertThat(vm.uiState.value.verifySha1OnLaunch).isFalse()
    }

    @Test
    fun `onVerifySha1OnLaunchChanged persists the toggle and updates state immediately`() = runBlocking {
        val (vm, mocks) = makeViewModel()

        vm.onVerifySha1OnLaunchChanged(true)
        assertThat(vm.uiState.value.verifySha1OnLaunch).isTrue()
        assertThat(mocks.verifySha1OnLaunch).isTrue()

        vm.onVerifySha1OnLaunchChanged(false)
        assertThat(vm.uiState.value.verifySha1OnLaunch).isFalse()
        assertThat(mocks.verifySha1OnLaunch).isFalse()
    }

    @Test
    fun `autocleanSavesOnUpload defaults to true when nothing is persisted`() = runBlocking {
        val (vm, _) = makeViewModel()

        assertThat(vm.uiState.value.autocleanSavesOnUpload).isTrue()
    }

    @Test
    fun `onAutocleanSavesOnUploadChanged persists the toggle and updates state immediately`() = runBlocking {
        val (vm, mocks) = makeViewModel()

        vm.onAutocleanSavesOnUploadChanged(false)
        assertThat(vm.uiState.value.autocleanSavesOnUpload).isFalse()
        assertThat(mocks.autocleanSavesOnUpload).isFalse()

        vm.onAutocleanSavesOnUploadChanged(true)
        assertThat(vm.uiState.value.autocleanSavesOnUpload).isTrue()
        assertThat(mocks.autocleanSavesOnUpload).isTrue()
    }
}
