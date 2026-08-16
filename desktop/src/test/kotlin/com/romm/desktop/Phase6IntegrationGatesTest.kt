package com.romm.desktop

import com.romm.androidtv.onboarding.OnboardingRoutingDecision.AppMode
import com.romm.androidtv.romm.ClientToken
import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.TestAppPaths
import com.romm.desktop.storage.secret.FakeSecretBackend
import com.sun.net.httpserver.HttpServer
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path

/**
 * Phase 6 integration gates (plans/PHASE6.md §4) exercised end-to-end through the real
 * [DesktopAppCoordinator] over its real storage seams (SQLite session records, fake keyring
 * token store, JSON settings) — no network except a local loopback HTTP server where a gate
 * requires observing actual request behavior.
 *
 * Gates already covered by focused tests are NOT duplicated here:
 *  - Gate 5 (settings persistence): `JsonSettingsStoreTest.write persists across a new instance`.
 *  - Gate 6 (controller navigation): `DesktopControllerRouterTest` (D-pad → Move, A/B → Activate/Back).
 *  - Gates 2/3 decision logic: `DesktopAppCoordinatorTest.decideAppMode *` (pure policy).
 *  - Onboarding presenter state machine incl. the one-shot Completed effect:
 *    shared/presentation `OnboardingPresenterTest`.
 */
@DisplayName("Phase 6 integration gates — coordinator end-to-end")
class Phase6IntegrationGatesTest {

    private companion object {
        const val ORIGIN = "https://romm.example.com"
        const val USER = "player1"
    }

    // ------------------------------------------------------------------ helpers

    /** A dispatcher that queues but never runs blocks: proof that nothing dispatched fires. */
    private class InertDispatcher : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            // Intentionally dropped — the block must never execute in these tests.
        }
    }

    private fun coordinator(
        paths: AppPaths,
        buildDefaultOrigin: String = ORIGIN,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) = DesktopAppCoordinator(
        paths = paths,
        secretBackend = FakeSecretBackend(),
        appVersion = "test",
        buildDefaultOrigin = buildDefaultOrigin,
        scope = scope,
    )

    private fun awaitUntil(timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for gate condition" }
            Thread.sleep(10)
        }
    }

    // ------------------------------------------------------------------ Gate 1

    @Test
    fun `gate 1 - fresh onboarding completion persists session record and token`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot(), scope = CoroutineScope(InertDispatcher()))

        // Fresh install: nothing durable yet.
        assertThat(c.sessionStorage.coherentRecord(ORIGIN)).isNull()
        assertThat(c.clientTokenStorage.getToken(ORIGIN, USER)).isNull()

        // Simulate the onboarding presenter's one-shot Completed effect: the login flow has
        // validated the origin, recorded the session, and persisted the client token.
        runBlocking { assertThat(c.settingsAdapter.persistValidatedOrigin(ORIGIN)).isTrue() }
        assertThat(c.sessionStorage.save(ORIGIN, USER)).isTrue()
        c.clientTokenStorage.setToken(ORIGIN, USER, ClientToken(raw = "tok-gate1"))

        // The host observes Completed and switches the whole app to MAIN.
        c.onOnboardingCompleted()

        assertThat(c.appMode).isEqualTo(AppMode.MAIN)
        assertThat(c.currentScreen).isEqualTo(Screen.HOME)
        val record = c.sessionStorage.coherentRecord(ORIGIN)
        assertThat(record).isNotNull
        assertThat(record!!.username).isEqualTo(USER)
        assertThat(c.clientTokenStorage.getToken(ORIGIN, USER)).isEqualTo(ClientToken(raw = "tok-gate1"))

        c.scope.cancel() // drop the queued (never-run) verification job
    }

    // ------------------------------------------------------------------ Gate 2

    @Test
    fun `gate 2 - restart skips onboarding only when session record and token are coherent`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        runBlocking { assertThat(c.settingsAdapter.persistValidatedOrigin(ORIGIN)).isTrue() }

        // Session record present but the token is missing: not fully onboarded yet.
        assertThat(c.sessionStorage.save(ORIGIN, USER)).isTrue()
        assertThat(c.computeStartupAppMode()).isEqualTo(AppMode.ONBOARDING)

        // Token persisted for the same scope: a restart now boots straight into MAIN.
        c.clientTokenStorage.setToken(ORIGIN, USER, ClientToken(raw = "tok-gate2"))
        assertThat(c.computeStartupAppMode()).isEqualTo(AppMode.MAIN)
    }

    // ------------------------------------------------------------------ Gate 3

    @Test
    fun `gate 3 - enterMainMode with an expired token falls back to onboarding and clears the session`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot(), scope = CoroutineScope(Dispatchers.Unconfined))
        runBlocking { assertThat(c.settingsAdapter.persistValidatedOrigin(ORIGIN)).isTrue() }
        assertThat(c.sessionStorage.save(ORIGIN, USER)).isTrue()

        // Simulate server-side token expiry: the durable token no longer verifies.
        c.clientTokenStorage.clearToken(ORIGIN, USER)

        c.enterMainMode()
        awaitUntil { c.appMode == AppMode.ONBOARDING }

        // The definitively-invalid session was cleared (record + token), not kept for retry.
        assertThat(c.sessionStorage.coherentRecord(ORIGIN)).isNull()
        assertThat(c.clientTokenStorage.getToken(ORIGIN, USER)).isNull()
    }

    // ------------------------------------------------------------------ Gate 4

    @Test
    fun `gate 4 - browse to detail screens sets selection state and back returns to the parent`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.appMode = AppMode.MAIN

        c.openCollectionDetail(42L)
        assertThat(c.currentScreen).isEqualTo(Screen.COLLECTION_DETAIL)
        assertThat(c.selectedCollectionId).isEqualTo(42L)
        c.onBack()
        assertThat(c.currentScreen).isEqualTo(Screen.COLLECTIONS)

        c.openPlatformDetail(7L)
        assertThat(c.currentScreen).isEqualTo(Screen.PLATFORM_DETAIL)
        assertThat(c.selectedPlatformId).isEqualTo(7L)

        c.openGameDetail(romId = 99L, parent = Screen.PLATFORM_DETAIL)
        assertThat(c.currentScreen).isEqualTo(Screen.GAME_DETAIL)
        assertThat(c.selectedRomId).isEqualTo(99L)
        // Back returns to the remembered parent that opened the detail.
        c.onBack()
        assertThat(c.currentScreen).isEqualTo(Screen.PLATFORM_DETAIL)
    }

    // ------------------------------------------------------------------ Gate 9

    @Test
    fun `gate 9 - no requests before onboarding completes, post-completion request carries the token`(@TempDir dir: Path) {
        val requests = mutableListOf<Pair<String, String?>>() // (path, Authorization header)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            synchronized(requests) {
                requests += exchange.requestURI.toString() to exchange.requestHeaders.getFirst("Authorization")
            }
            val body = "{}".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val origin = "http://127.0.0.1:${server.address.port}"
            val c = coordinator(dir.testRoot(), buildDefaultOrigin = origin, scope = CoroutineScope(Dispatchers.Unconfined))

            // Before onboarding completes: the app must not make a single network request.
            assertThat(c.appMode).isEqualTo(AppMode.ONBOARDING)
            c.onboardingPresenter() // constructing the presenter must not reach out either
            synchronized(requests) { assertThat(requests).isEmpty() }

            // Main-mode actions are inert while in ONBOARDING mode.
            c.onBack()
            assertThat(c.exitRequested).isFalse()

            // Simulate completed onboarding for this origin, then enter main mode.
            runBlocking { assertThat(c.settingsAdapter.persistValidatedOrigin(origin)).isTrue() }
            assertThat(c.sessionStorage.save(origin, USER)).isTrue()
            c.clientTokenStorage.setToken(origin, USER, ClientToken(raw = "tok-gate9"))

            c.onOnboardingCompleted()
            awaitUntil { synchronized(requests) { requests.isNotEmpty() } }

            // The first (and only expected) request is the authorized durable-session check.
            val (path, authorization) = synchronized(requests) { requests.first() }
            assertThat(path).startsWith("/")
            assertThat(authorization).isNotNull().contains("tok-gate9")
        } finally {
            server.stop(0)
        }
    }

    private fun Path.testRoot(): AppPaths = TestAppPaths(this)
}
