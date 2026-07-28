package com.romm.androidtv.auth

import com.romm.androidtv.config.FakeSharedPreferences
import com.romm.androidtv.network.AuthError
import com.romm.androidtv.network.AuthFlowResult
import com.romm.androidtv.network.HeartbeatCallResult
import com.romm.androidtv.network.RomMCookieSync
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.CookieManager
import java.net.CookiePolicy

class AuthRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: okhttp3.OkHttpClient
    private lateinit var sessionStore: SessionStore
    private lateinit var repository: AuthRepository

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start(0)
        client = okhttp3.OkHttpClient.Builder().build()
        val cookieSync = RomMCookieSync(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        sessionStore = SessionStore(FakeSharedPreferences())
        repository = AuthRepository(client, cookieSync, sessionStore)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    @Test
    fun `login records a session on success`() = runBlocking {
        // POST /api/login
        server.enqueue(MockResponse().setResponseCode(200))
        // heartbeat after login
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"version":"5.0.0","setup_complete":true,"userpass_enabled":true,"emulatorjs_enabled":true}"""
            )
        )
        // /api/users/me
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"root","admin":true}"""))

        val result = repository.login(baseUrl(), "root", "hunter2".toCharArray())

        assertThat(result).isInstanceOf(AuthFlowResult.Success::class.java)
        val stored = sessionStore.current()
        assertThat(stored).isNotNull
        assertThat(stored!!.origin).isEqualTo(baseUrl())
        assertThat(stored.username).isEqualTo("root")
    }

    @Test
    fun `login does not record a session on failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = repository.login(baseUrl(), "root", "wrong".toCharArray())

        assertThat(result).isInstanceOf(AuthFlowResult.Failure::class.java)
        assertThat((result as AuthFlowResult.Failure).error).isEqualTo(AuthError.INVALID_CREDENTIALS)
        assertThat(sessionStore.current()).isNull()
    }

    @Test
    fun `verifySession records a session on success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"root","admin":true}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"version":"5.0.0","setup_complete":true,"userpass_enabled":true,"emulatorjs_enabled":true}"""
            )
        )

        val result = repository.verifySession(baseUrl())

        assertThat(result).isInstanceOf(AuthFlowResult.Success::class.java)
        assertThat(sessionStore.current()?.username).isEqualTo("root")
    }

    @Test
    fun `verifySession does not record a session on expired session`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = repository.verifySession(baseUrl())

        assertThat(result).isInstanceOf(AuthFlowResult.Failure::class.java)
        assertThat(sessionStore.current()).isNull()
    }

    @Test
    fun `checkHeartbeat delegates to executeHeartbeat`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"version":"5.0.0","setup_complete":true,"userpass_enabled":true,"emulatorjs_enabled":true}"""
            )
        )

        val result = repository.checkHeartbeat(baseUrl())

        assertThat(result).isInstanceOf(HeartbeatCallResult.Success::class.java)
    }
}
