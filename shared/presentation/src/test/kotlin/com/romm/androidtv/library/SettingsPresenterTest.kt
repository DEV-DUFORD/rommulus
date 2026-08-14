package com.romm.androidtv.library

import com.romm.androidtv.auth.SessionStorage
import com.romm.androidtv.config.ServerProfile
import com.romm.androidtv.model.HeartbeatError
import com.romm.androidtv.model.HeartbeatResponse
import com.romm.androidtv.network.HeartbeatCallResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * JVM unit tests for [SettingsPresenter]: origin validation, save/restore state
 * transitions, connection check states, and session invalidation on origin change.
 *
 * Uses functional lambdas as dependency mocks — no concrete class extension needed.
 * The presenter runs on an unconfined virtual-time [TestScope], so launched
 * coroutines execute immediately and every interaction is observable synchronously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("SettingsPresenter — validation, save, restore, connection, session")
class SettingsPresenterTest {

    /** Virtual-time-capable test scope driving the presenter (unconfined ⇒ immediate execution). */
    private lateinit var testScope: TestScope

    @BeforeEach
    fun setUp() {
        testScope = TestScope(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        testScope.coroutineContext[Job]?.cancel()
    }

    // ---- Helper to construct presenter with configurable mocks ----

    private data class TestMocks(
        var storedOrigin: String = "",
        var sessionRecord: SessionStorage.Record? = null,
        var invalidated: Boolean = false,
        var heartbeatCalls: Int = 0,
        var loginCalls: Int = 0,
        var loginSuccessInvoked: Boolean = false,
        var verifySha1OnLaunch: Boolean = false,
        var autocleanSavesOnUpload: Boolean = true,
    )

    private fun makePresenter(
        initialOrigin: String = "",
        initialSession: SessionStorage.Record? = null,
        buildDefault: String = "https://build-default.example.com",
        heartbeatResult: HeartbeatCallResult = HeartbeatCallResult.Failure(HeartbeatError.NETWORK_ERROR),
        loginResult: com.romm.androidtv.network.AuthFlowResult = com.romm.androidtv.network.AuthFlowResult.Failure(
            com.romm.androidtv.network.AuthError.NETWORK_ERROR,
        ),
    ): Pair<SettingsPresenter, TestMocks> {
        val mocks = TestMocks(
            storedOrigin = initialOrigin,
            sessionRecord = initialSession,
        )

        // Mutable references so lambdas capture the latest value
        @Suppress("UNUSED_ANONYMOUS_PARAMETER")
        val presenter = SettingsPresenter(
            scope = testScope,
            getCurrentProfile = { ServerProfile(origin = if (mocks.storedOrigin.isBlank()) buildDefault else mocks.storedOrigin) },
            setOriginFn = { origin -> mocks.storedOrigin = if (origin.isBlank()) "" else origin },
            clearOverrideFn = { mocks.storedOrigin = "" },
            getSessionRecord = { mocks.sessionRecord },
            clearSessionFn = { mocks.sessionRecord = null },
            checkHeartbeatFn = { _ -> mocks.heartbeatCalls++; heartbeatResult },
            loginFn = { _, _, _ -> mocks.loginCalls++; loginResult },
            onLoginSuccess = { mocks.loginSuccessInvoked = true },
            onSessionInvalidated = { mocks.invalidated = true },
            getVerifySha1OnLaunch = { mocks.verifySha1OnLaunch },
            setVerifySha1OnLaunchFn = { verify -> mocks.verifySha1OnLaunch = verify },
            getAutocleanSavesOnUpload = { mocks.autocleanSavesOnUpload },
            setAutocleanSavesOnUploadFn = { enabled -> mocks.autocleanSavesOnUpload = enabled },
            appVersion = "1.0.0-test",
            buildDefaultOrigin = buildDefault,
        )

        return presenter to mocks
    }

    // ---- Tests ----

    @Test
    fun `initial state loads persisted origin and session info`() {
        val (presenter, mocks) = makePresenter(
            initialOrigin = "https://persisted.example.com",
            initialSession = SessionStorage.Record("https://persisted.example.com", "testuser", System.currentTimeMillis()),
        )

        val state = presenter.uiState.value
        assertThat(state.originText).isEqualTo("https://persisted.example.com")
        assertThat(state.currentOrigin).isEqualTo("https://persisted.example.com")
        assertThat(state.currentUsername).isEqualTo("testuser")
        assertThat(state.validationError).isNull()
        assertThat(mocks.invalidated).isFalse()
    }

    @Test
    fun `validateOrigin rejects blank string`() {
        val (presenter, _) = makePresenter()
        assertThat(presenter.validateOrigin("")).isEqualTo("Server address is required")
    }

    @Test
    fun `validateOrigin rejects whitespace-only string`() {
        val (presenter, _) = makePresenter()
        assertThat(presenter.validateOrigin("   ")).isEqualTo("Server address is required")
    }

    @Test
    fun `validateOrigin rejects invalid URL format`() {
        val (presenter, _) = makePresenter()
        assertThat(presenter.validateOrigin("https://exa mple.com")).isEqualTo("Invalid URL format")
    }

    @Test
    fun `validateOrigin rejects unsupported scheme`() {
        val (presenter, _) = makePresenter()
        assertThat(presenter.validateOrigin("ftp://example.com")).isEqualTo("Only HTTP and HTTPS schemes are supported")
    }

    @Test
    fun `validateOrigin accepts valid https URL`() {
        val (presenter, _) = makePresenter()
        assertThat(presenter.validateOrigin("https://romm.example.com")).isNull()
    }

    @Test
    fun `validateOrigin accepts valid http URL`() {
        val (presenter, _) = makePresenter()
        assertThat(presenter.validateOrigin("http://localhost:8080")).isNull()
    }

    @Test
    fun `validateOrigin accepts URL with subpath`() {
        val (presenter, _) = makePresenter()
        assertThat(presenter.validateOrigin("https://example.com/romm")).isNull()
    }

    // ---- onOriginTextChanged tests ----

    @Test
    fun `onOriginTextChanged updates text and clears previous feedback`() {
        val (presenter, _) = makePresenter(initialOrigin = "https://default.example.com")
        presenter.onOriginTextChanged("https://new.example.com")

        val state = presenter.uiState.value
        assertThat(state.originText).isEqualTo("https://new.example.com")
        assertThat(state.validationError).isNull()
        assertThat(state.saveSuccessMessage).isNull()
        assertThat(state.saveErrorMessage).isNull()
    }

    @Test
    fun `onOriginTextChanged sets validation error for invalid input`() {
        val (presenter, _) = makePresenter(initialOrigin = "https://default.example.com")
        presenter.onOriginTextChanged("https://exa mple.com")

        val state = presenter.uiState.value
        assertThat(state.validationError).isEqualTo("Invalid URL format")
    }

    @Test
    fun `onOriginTextChanged detects origin changed`() {
        val (presenter, _) = makePresenter(initialOrigin = "https://default.example.com")
        assertThat(presenter.uiState.value.originChanged).isFalse()

        presenter.onOriginTextChanged("https://new.example.com")
        assertThat(presenter.uiState.value.originChanged).isTrue()
    }

    // ---- onSave tests ----

    @Test
    fun `onSave with invalid origin shows error and does not persist`() {
        val (presenter, mocks) = makePresenter(initialOrigin = "https://current.example.com")
        presenter.onOriginTextChanged("https://exa mple.com")
        presenter.onSave()

        val state = presenter.uiState.value
        assertThat(state.validationError).isEqualTo("Invalid URL format")
        assertThat(state.saveErrorMessage).isNotNull()
        assertThat(state.saveSuccessMessage).isNull()
        assertThat(mocks.storedOrigin).isEqualTo("https://current.example.com")
    }

    @Test
    fun `onSave with valid unchanged origin shows no changes`() {
        val (presenter, mocks) = makePresenter(
            initialOrigin = "https://current.example.com",
            initialSession = SessionStorage.Record("https://current.example.com", "user", System.currentTimeMillis()),
        )
        presenter.onSave()

        val state = presenter.uiState.value
        assertThat(state.saveSuccessMessage).isEqualTo("No changes")
        assertThat(mocks.invalidated).isFalse()
    }

    @Test
    fun `onSave with changed origin persists and invalidates session`() {
        val (presenter, mocks) = makePresenter(
            initialOrigin = "https://old.example.com",
            initialSession = SessionStorage.Record("https://old.example.com", "user", System.currentTimeMillis()),
        )

        presenter.onOriginTextChanged("https://new.example.com")
        presenter.onSave()

        // Origin persisted
        assertThat(mocks.storedOrigin).isEqualTo("https://new.example.com")
        // Session cleared
        assertThat(mocks.sessionRecord).isNull()
        // Callback fired
        assertThat(mocks.invalidated).isTrue()
        // Success message shown
        val state = presenter.uiState.value
        assertThat(state.saveSuccessMessage).contains("session cleared")
    }

    @Test
    fun `onSave with same origin normalized does not invalidate session`() {
        val (presenter, mocks) = makePresenter(
            initialOrigin = "https://current.example.com",
            initialSession = SessionStorage.Record("https://current.example.com", "user", System.currentTimeMillis()),
        )

        // Edit to equivalent URL (same origin with explicit default port)
        presenter.onOriginTextChanged("https://current.example.com:443")
        presenter.onSave()

        assertThat(mocks.invalidated).isFalse()
        val state = presenter.uiState.value
        // NOTE: the :app original asserted "Saved" here, but that test method compiled
        // to a non-void return type in :app (Kotlin inferred it from `= runBlocking { ... }`),
        // so JUnit never discovered or ran it. A same-origin save takes the no-change path
        // and reports "No changes"; the session-preservation assertions are unchanged.
        assertThat(state.saveSuccessMessage).isEqualTo("No changes")
    }

    // ---- onRestoreDefault tests ----

    @Test
    fun `onRestoreDefault clears override and shows build default`() {
        val (presenter, mocks) = makePresenter(initialOrigin = "https://custom.example.com")
        presenter.onRestoreDefault()

        assertThat(mocks.storedOrigin).isEmpty()
        val state = presenter.uiState.value
        assertThat(state.originText).isEqualTo("https://build-default.example.com")
        assertThat(state.saveSuccessMessage).isEqualTo("Restored to build default")
    }

    @Test
    fun `onRestoreDefault with changed origin and active session invalidates session`() {
        val (presenter, mocks) = makePresenter(
            initialOrigin = "https://custom.example.com",
            initialSession = SessionStorage.Record("https://custom.example.com", "user", System.currentTimeMillis()),
        )

        presenter.onRestoreDefault()

        assertThat(mocks.storedOrigin).isEmpty()
        assertThat(mocks.sessionRecord).isNull()
        assertThat(mocks.invalidated).isTrue()
        val state = presenter.uiState.value
        assertThat(state.originText).isEqualTo("https://build-default.example.com")
        assertThat(state.saveSuccessMessage).contains("session cleared")
    }

    @Test
    fun `onRestoreDefault with same origin normalized does not invalidate session`() {
        // Single captured timestamp: comparing two separate System.currentTimeMillis()
        // calls is flaky across a millisecond boundary (the :app original had this bug).
        val now = System.currentTimeMillis()
        val record = SessionStorage.Record("https://build-default.example.com", "user", now)
        val (presenter, mocks) = makePresenter(
            initialOrigin = "https://build-default.example.com",
            initialSession = record,
        )

        presenter.onRestoreDefault()

        assertThat(mocks.invalidated).isFalse()
        assertThat(mocks.sessionRecord).isEqualTo(record)
        val state = presenter.uiState.value
        assertThat(state.saveSuccessMessage).isEqualTo("Restored to build default")
    }

    // ---- onCheckConnection tests ----

    @Test
    fun `onCheckConnection with invalid origin shows error immediately`() {
        val (presenter, mocks) = makePresenter()
        presenter.onOriginTextChanged("https://exa mple.com")
        presenter.onCheckConnection()

        val state = presenter.uiState.value
        assertThat(state.connectionCheck).isInstanceOf(ConnectionCheckState.Error::class.java)
        assertThat((state.connectionCheck as ConnectionCheckState.Error).message).isEqualTo("Invalid URL format")
        assertThat(mocks.heartbeatCalls).isEqualTo(0) // No network call made
    }

    @Test
    fun `onCheckConnection with valid origin fires heartbeat and shows success`() {
        val (presenter, mocks) = makePresenter(
            heartbeatResult = HeartbeatCallResult.Success(HeartbeatResponse("1.2.3", true, true, true)),
        )
        presenter.onOriginTextChanged("https://example.com")
        presenter.onCheckConnection()

        assertThat(mocks.heartbeatCalls).isEqualTo(1)
        val state = presenter.uiState.value
        assertThat(state.connectionCheck).isInstanceOf(ConnectionCheckState.Success::class.java)
        assertThat((state.connectionCheck as ConnectionCheckState.Success).version).isEqualTo("1.2.3")
    }

    @Test
    fun `onCheckConnection formats heartbeat error`() {
        val (presenter, _) = makePresenter(
            heartbeatResult = HeartbeatCallResult.Failure(HeartbeatError.NETWORK_ERROR),
        )
        presenter.onOriginTextChanged("https://unreachable.example.com")
        presenter.onCheckConnection()

        val state = presenter.uiState.value
        assertThat(state.connectionCheck).isInstanceOf(ConnectionCheckState.Error::class.java)
        assertThat((state.connectionCheck as ConnectionCheckState.Error).message).isEqualTo("Network unreachable")
    }

    // ---- Session invalidation edge cases ----

    @Test
    fun `onSave with changed origin but no session does not call invalidation callback`() {
        val (presenter, mocks) = makePresenter(initialOrigin = "https://old.example.com")
        // No session record

        presenter.onOriginTextChanged("https://new.example.com")
        presenter.onSave()

        assertThat(mocks.invalidated).isFalse()
        val state = presenter.uiState.value
        assertThat(state.saveSuccessMessage).isEqualTo("Saved")
    }

    @Test
    fun `connection check TLS error is formatted`() {
        val (presenter, _) = makePresenter(
            heartbeatResult = HeartbeatCallResult.Failure(HeartbeatError.TLS_ERROR),
        )
        presenter.onOriginTextChanged("https://bad-cert.example.com")
        presenter.onCheckConnection()

        val state = presenter.uiState.value
        assertThat(state.connectionCheck).isInstanceOf(ConnectionCheckState.Error::class.java)
        assertThat((state.connectionCheck as ConnectionCheckState.Error).message).isEqualTo("TLS / certificate error")
    }

    @Test
    fun `connection check HTTP error is formatted`() {
        val (presenter, _) = makePresenter(
            heartbeatResult = HeartbeatCallResult.Failure(HeartbeatError.HTTP_ERROR),
        )
        presenter.onOriginTextChanged("https://example.com")
        presenter.onCheckConnection()

        val state = presenter.uiState.value
        assertThat(state.connectionCheck).isInstanceOf(ConnectionCheckState.Error::class.java)
        assertThat((state.connectionCheck as ConnectionCheckState.Error).message).isEqualTo("HTTP error from server")
    }

    // ---- Native credentials login (Settings screen) ----

    @Test
    fun `login succeeds, clears password, and notifies onLoginSuccess`() {
        val (presenter, mocks) = makePresenter(
            initialOrigin = "https://romm.example.com",
            loginResult = com.romm.androidtv.network.AuthFlowResult.Success(
                heartbeatAfterLogin = com.romm.androidtv.model.HeartbeatResponse(
                    version = "5.0.0", setupComplete = true, userpassEnabled = true, emulatorJsEnabled = true,
                ),
                verifiedUser = com.romm.androidtv.network.VerifiedUser(username = "root", isAdmin = true),
            ),
        )

        presenter.onUsernameTextChanged("root")
        presenter.onPasswordTextChanged("hunter2")
        presenter.onLogin()

        val state = presenter.uiState.value
        assertThat(mocks.loginCalls).isEqualTo(1)
        assertThat(mocks.loginSuccessInvoked).isTrue()
        assertThat(state.loginState).isInstanceOf(SettingsLoginState.Success::class.java)
        assertThat(state.passwordText).isEmpty()
        assertThat(state.currentUsername).isEqualTo("root")
    }

    @Test
    fun `login failure surfaces a formatted error and clears password without navigating`() {
        val (presenter, mocks) = makePresenter(
            initialOrigin = "https://romm.example.com",
            loginResult = com.romm.androidtv.network.AuthFlowResult.Failure(
                com.romm.androidtv.network.AuthError.INVALID_CREDENTIALS,
            ),
        )

        presenter.onUsernameTextChanged("root")
        presenter.onPasswordTextChanged("wrong")
        presenter.onLogin()

        val state = presenter.uiState.value
        assertThat(mocks.loginSuccessInvoked).isFalse()
        assertThat(state.loginState).isInstanceOf(SettingsLoginState.Error::class.java)
        assertThat((state.loginState as SettingsLoginState.Error).message).isEqualTo("Invalid username or password")
        assertThat(state.passwordText).isEmpty()
    }

    @Test
    fun `login with blank username or password is rejected without a network call`() {
        val (presenter, mocks) = makePresenter(initialOrigin = "https://romm.example.com")

        presenter.onUsernameTextChanged("")
        presenter.onPasswordTextChanged("")
        presenter.onLogin()

        assertThat(mocks.loginCalls).isEqualTo(0)
        assertThat(presenter.uiState.value.loginState).isInstanceOf(SettingsLoginState.Error::class.java)
    }

    @Test
    fun `login with an invalid origin is rejected without a network call`() {
        // NOTE: the :app original used initialOrigin = "", but the harness falls back to the
        // valid build-default origin for blank profiles (mirroring production), which would
        // let the login proceed. A syntactically invalid persisted origin exercises the
        // intended rejection path (bare hostnames like "not-a-url" are NOT usable here:
        // RommServerAddress infers https:// for them and accepts them). This test was also
        // never run in :app (non-void compiled signature ⇒ invisible to JUnit discovery).
        val (presenter, mocks) = makePresenter(initialOrigin = "https://exa mple.com")

        presenter.onUsernameTextChanged("root")
        presenter.onPasswordTextChanged("hunter2")
        presenter.onLogin()

        assertThat(mocks.loginCalls).isEqualTo(0)
        assertThat(presenter.uiState.value.loginState).isInstanceOf(SettingsLoginState.Error::class.java)
    }

    @Test
    fun `initial state loads verifySha1OnLaunch as false when nothing is persisted`() {
        val (presenter, _) = makePresenter()

        assertThat(presenter.uiState.value.verifySha1OnLaunch).isFalse()
    }

    @Test
    fun `onVerifySha1OnLaunchChanged persists the toggle and updates state immediately`() {
        val (presenter, mocks) = makePresenter()

        presenter.onVerifySha1OnLaunchChanged(true)
        assertThat(presenter.uiState.value.verifySha1OnLaunch).isTrue()
        assertThat(mocks.verifySha1OnLaunch).isTrue()

        presenter.onVerifySha1OnLaunchChanged(false)
        assertThat(presenter.uiState.value.verifySha1OnLaunch).isFalse()
        assertThat(mocks.verifySha1OnLaunch).isFalse()
    }

    @Test
    fun `autocleanSavesOnUpload defaults to true when nothing is persisted`() {
        val (presenter, _) = makePresenter()

        assertThat(presenter.uiState.value.autocleanSavesOnUpload).isTrue()
    }

    @Test
    fun `onAutocleanSavesOnUploadChanged persists the toggle and updates state immediately`() {
        val (presenter, mocks) = makePresenter()

        presenter.onAutocleanSavesOnUploadChanged(false)
        assertThat(presenter.uiState.value.autocleanSavesOnUpload).isFalse()
        assertThat(mocks.autocleanSavesOnUpload).isFalse()

        presenter.onAutocleanSavesOnUploadChanged(true)
        assertThat(presenter.uiState.value.autocleanSavesOnUpload).isTrue()
        assertThat(mocks.autocleanSavesOnUpload).isTrue()
    }
}
